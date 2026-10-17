package ru.vasev.glasses.glasses

import android.content.Context
import android.graphics.ImageDecoder
import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.camera.ImagePrep

/** Ошибка с текстом, который можно сразу показать пользователю. */
class GlassesException(message: String) : Exception(message)

data class GlassesStatus(
    val mockMode: Boolean,
    val registration: RegistrationState,
    val deviceCount: Int,
) {
    val ready: Boolean get() = registration == RegistrationState.REGISTERED && deviceCount > 0

    val text: String
        get() = when {
            registration == RegistrationState.REGISTERING -> "Регистрация в Meta AI…"
            registration != RegistrationState.REGISTERED -> "Приложение не подключено к Meta AI"
            deviceCount == 0 -> "Очки не найдены"
            mockMode -> "Симулятор очков готов"
            else -> "Очки подключены"
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

    init {
        if (_mockMode.value) scope.launch { mock.enable() }
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
            throw GlassesException("Приложение не подключено к Meta AI. Нажмите «Подключить очки».")
        }
        if (current.deviceCount == 0) throw GlassesException("Очки не найдены. Наденьте их и проверьте Bluetooth.")

        ensureCameraPermission(requestCameraPermission)

        val session = Wearables.createSession(AutoDeviceSelector()).getOrNull()
            ?: throw GlassesException("Не удалось открыть сессию с очками.")
        var camera: Camera? = null
        try {
            session.start()
            awaitSessionStarted(session)

            camera = session.addCamera(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15),
            ).let { result ->
                result.getOrNull() ?: throw GlassesException(
                    "Камера недоступна: ${result.errorOrNull()?.description ?: "неизвестная ошибка"}",
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
                    stream.start().errorOrNull()?.let { throw GlassesException("Стрим не запустился: ${it.description}") }
                    val outcome = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                        combine(stream.state, critical) { state, error -> error ?: state }
                            .first { it == StreamState.STREAMING || it == StreamState.CLOSED || it is StreamError }
                    }
                    when (outcome) {
                        is StreamError, StreamState.CLOSED ->
                            throw GlassesException("Камера очков не запустилась. Попробуйте ещё раз.")
                        null -> Log.w(TAG, "Stream state is ${stream.state.value}, trying capture anyway")
                    }
                } finally {
                    errors.cancel()
                }
            }

            val photo = stream.capturePhoto()
            val data = photo.getOrNull()
                ?: throw GlassesException("Не удалось сделать снимок: ${photo.errorOrNull()?.description}")
            return withContext(Dispatchers.Default) { toJpeg(data) }
        } finally {
            runCatching { camera?.stop() }
            runCatching { session.stop() }
        }
    }

    private suspend fun ensureCameraPermission(request: suspend () -> Boolean) {
        val status = Wearables.checkPermissionStatus(Permission.CAMERA)
        if (status.getOrNull() is PermissionStatus.Granted) return
        if (!request()) throw GlassesException("Нет доступа к камере очков. Разрешите его в Meta AI.")
    }

    private suspend fun awaitSessionStarted(session: DeviceSession) {
        val state = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            session.state.first {
                it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
            }
        }
        if (state != DeviceSessionState.STARTED) {
            throw GlassesException("Очки не отвечают. Проверьте, что они включены и надеты.")
        }
    }

    private fun toJpeg(data: PhotoData): ByteArray {
        val bitmap = when (data) {
            is PhotoData.Bitmap -> data.bitmap
            is PhotoData.HEIC -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(data.data)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        return ImagePrep.fromBitmap(bitmap, source = if (_mockMode.value) "симулятор" else "очки")
    }

    private companion object {
        const val KEY_MOCK = "mock_mode"
        const val SESSION_TIMEOUT_MS = 15_000L
        const val STREAM_TIMEOUT_MS = 10_000L
    }
}
