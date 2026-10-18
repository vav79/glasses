package ru.vasev.glasses.glasses

import android.content.Context
import android.graphics.ImageDecoder
import android.util.Log
import androidx.annotation.StringRes
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.camera.types.StreamError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.i18n.L

/** Ошибка с текстом, который можно сразу показать пользователю. */
class GlassesException(message: String) : Exception(message)

data class GlassesStatus(
    val mockMode: Boolean,
    val registration: RegistrationState,
    val deviceCount: Int,
) {
    val ready: Boolean get() = registration == RegistrationState.REGISTERED && deviceCount > 0

    @get:StringRes
    val textRes: Int
        get() = when {
            registration == RegistrationState.REGISTERING -> R.string.glasses_registering
            registration != RegistrationState.REGISTERED -> R.string.glasses_not_registered
            deviceCount == 0 -> R.string.glasses_not_found
            mockMode -> R.string.glasses_simulator_ready
            else -> R.string.glasses_connected
        }
}

/**
 * Связь с очками через DAT SDK: режим «симулятор / настоящие очки» и съёмка одного кадра.
 * Для каждого снимка открывается короткая сессия: сессия → камера → стрим → capturePhoto → стоп.
 */
class GlassesManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("glasses", Context.MODE_PRIVATE)
    private val mock = MockGlassesController(context)

    // Режим задаёт источник снимков (ImageSourceSettings); в release-сборке симулятора нет.
    private val _mockMode = MutableStateFlow(BuildConfig.DEBUG && prefs.getBoolean(KEY_MOCK, false))
    val mockMode: StateFlow<Boolean> = _mockMode.asStateFlow()

    val status: StateFlow<GlassesStatus> =
        combine(_mockMode, Wearables.registrationState, Wearables.devices) { mockMode, registration, devices ->
            GlassesStatus(mockMode, registration, devices.size)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            GlassesStatus(_mockMode.value, Wearables.registrationState.value, Wearables.devices.value.size),
        )

    /** Заряд подключённых очков в процентах; null — очков нет или они ещё не прислали значение. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val battery: StateFlow<Int?> = Wearables.devices
        .flatMapLatest { ids ->
            ids.firstOrNull()?.let { Wearables.devicesMetadata[it] }?.map { device -> device.batteryLevel.takeIf { it in 0..100 } }
                ?: flowOf(null)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _everConnected = MutableStateFlow(prefs.getBoolean(KEY_EVER_CONNECTED, false))

    /** Очки хоть раз подключались: пока нет и снимки с телефона, индикатор очков в «Ассистенте» не показывается. */
    val everConnected: StateFlow<Boolean> = _everConnected.asStateFlow()

    init {
        if (_mockMode.value) scope.launch { mock.enable() }
        scope.launch {
            status.collect { if (it.ready && !_everConnected.value) {
                prefs.edit().putBoolean(KEY_EVER_CONNECTED, true).apply()
                _everConnected.value = true
            } }
        }
    }

    fun setMockMode(requested: Boolean) {
        val enabled = requested && BuildConfig.DEBUG
        if (enabled == _mockMode.value) return
        prefs.edit().putBoolean(KEY_MOCK, enabled).apply()
        _mockMode.value = enabled
        scope.launch { if (enabled) mock.enable() else mock.disable() }
    }

    /**
     * Делает один снимок с камеры очков и возвращает JPEG (длинная сторона ≤ [MAX_IMAGE_SIDE]).
     * [requestCameraPermission] показывает системный диалог Meta AI, если доступа к камере ещё нет.
     */
    suspend fun captureJpeg(requestCameraPermission: suspend () -> Boolean): ByteArray {
        val current = status.value
        if (current.registration != RegistrationState.REGISTERED) {
            throw GlassesException(L.s(R.string.glasses_err_not_registered))
        }
        if (current.deviceCount == 0) throw GlassesException(L.s(R.string.glasses_err_not_found))

        ensureCameraPermission(requestCameraPermission)

        val session = Wearables.createSession(AutoDeviceSelector()).getOrNull()
            ?: throw GlassesException(L.s(R.string.glasses_err_session))
        var camera: Camera? = null
        try {
            session.start()
            awaitSessionStarted(session)

            camera = session.addCamera(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15),
            ).let { result ->
                result.getOrNull() ?: throw GlassesException(
                    L.s(R.string.glasses_err_camera_unavailable, result.errorOrNull()?.description ?: L.s(R.string.glasses_err_unknown)),
                )
            }

            val stream = camera.stream
            coroutineScope {
                // Подписываемся до start(): критическая ошибка приходит асинхронно и сразу.
                val critical = MutableStateFlow<StreamError?>(null)
                val errors = launch(start = CoroutineStart.UNDISPATCHED) {
                    stream.errorStream.collect { if (it == StreamError.CRITICAL_STREAM_ERROR) critical.value = it }
                }
                try {
                    stream.start().errorOrNull()?.let { throw GlassesException(L.s(R.string.glasses_err_stream_start, it.description)) }
                    val outcome = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                        combine(stream.state, critical) { state, error -> error ?: state }
                            .first { it == StreamState.STREAMING || it == StreamState.CLOSED || it is StreamError }
                    }
                    when (outcome) {
                        is StreamError, StreamState.CLOSED ->
                            throw GlassesException(L.s(R.string.glasses_err_camera_start))
                        null -> Log.w(TAG, "Stream state is ${stream.state.value}, trying capture anyway")
                    }
                } finally {
                    errors.cancel()
                }
            }

            val photo = stream.capturePhoto()
            val data = photo.getOrNull()
                ?: throw GlassesException(L.s(R.string.glasses_err_capture, photo.errorOrNull()?.description.orEmpty()))
            return withContext(Dispatchers.Default) { toJpeg(data) }
        } finally {
            runCatching { camera?.stop() }
            runCatching { session.stop() }
        }
    }

    private suspend fun ensureCameraPermission(request: suspend () -> Boolean) {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA)
        if (status.getOrNull() is PermissionStatus.Granted) return
        if (!request()) throw GlassesException(L.s(R.string.glasses_err_camera_permission))
    }

    private suspend fun awaitSessionStarted(session: DeviceSession) {
        val state = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            session.state.first {
                it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
            }
        }
        if (state != DeviceSessionState.STARTED) {
            throw GlassesException(L.s(R.string.glasses_err_not_responding))
        }
    }

    private fun toJpeg(data: PhotoData): ByteArray {
        val bitmap = when (data) {
            is PhotoData.Bitmap -> data.bitmap
            is PhotoData.HEIC -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(data.data)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        return ImagePrep.fromBitmap(bitmap, source = (if (_mockMode.value) ImageSource.SIMULATOR else ImageSource.GLASSES).logName)
    }

    private companion object {
        const val KEY_MOCK = "mock_mode"
        const val KEY_EVER_CONNECTED = "ever_connected"
        const val SESSION_TIMEOUT_MS = 15_000L
        const val STREAM_TIMEOUT_MS = 10_000L
    }
}
