package ru.vasev.glasses.camera

import android.content.Context
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import android.util.Log
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhoneCameraException(message: String) : Exception(message)

/**
 * Задняя камера телефона через CameraX: видоискатель и один снимок.
 * Камера включена только пока на экране видоискатель ([bind] / [unbind] из Compose), в фоне не работает.
 */
class PhoneCamera(private val context: Context) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var bound = false

    /** Камеру держит видоискатель (а не разовый снимок). */
    @Volatile
    private var previewBound = false

    // ~2 Мп хватает с запасом: дальше снимок всё равно уменьшается до 1024 px.
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .build()

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                bound = true
                previewBound = true
                Log.d(TAG, "phone camera on")
            } catch (e: Exception) {
                bound = false
                Log.w(TAG, "phone camera failed to start: ${e.javaClass.simpleName}")
            }
        }, mainExecutor)
    }

    /**
     * Снимок без видоискателя (кнопка «Что я вижу» в «Ассистенте» при источнике «Камера телефона»): камера
     * включается только на этот снимок и сразу выключается. Видоискатель на экране — обычный [capture].
     */
    suspend fun captureWithoutPreview(owner: LifecycleOwner): ByteArray {
        if (bound) return capture()
        val cameraProvider = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({ cont.resume(future.get()) }, mainExecutor)
            }
        }
        provider = cameraProvider
        val startedAt = SystemClock.elapsedRealtime()
        val camera = withContext(Dispatchers.Main) {
            try {
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
            } catch (e: Exception) {
                Log.w(TAG, "phone camera failed to start: ${e.javaClass.simpleName}")
                throw PhoneCameraException(L.s(R.string.phone_camera_failed))
            }
        }
        bound = true
        Log.d(TAG, "phone camera on for one shot")
        try {
            // Камера только что включилась: без этого снимок уходил до того, как автофокус и экспозиция установятся.
            val focused = focusCenter(camera)
            Log.d(TAG, "one shot focus=$focused after=${SystemClock.elapsedRealtime() - startedAt}ms")
            return capture()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                // Пока снимали, мог открыться видоискатель (вкладка «Камера») — его не трогаем.
                if (!previewBound) {
                    cameraProvider.unbind(imageCapture)
                    bound = false
                    Log.d(TAG, "phone camera off")
                }
            }
        }
    }

    /**
     * Фокус и экспозиция по центру кадра; ждём не дольше [FOCUS_TIMEOUT_MS]. true — камера сообщила «сфокусировано»;
     * false — не успела или не смогла (темно, не за что зацепиться): снимаем всё равно — лучше нечёткий кадр, чем никакого.
     */
    private suspend fun focusCenter(camera: Camera): Boolean {
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        return withTimeoutOrNull(FOCUS_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val future = camera.cameraControl.startFocusAndMetering(action)
                    cont.invokeOnCancellation { future.cancel(false) }
                    future.addListener({
                        val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                        if (cont.isActive) cont.resume(ok)
                    }, mainExecutor)
                }
            }
        } ?: false
    }

    fun unbind() {
        provider?.unbindAll()
        previewBound = false
        if (bound) Log.d(TAG, "phone camera off")
        bound = false
    }

    /** Снимок с учётом поворота, уменьшенный и без метаданных. */
    suspend fun capture(): ByteArray {
        if (!bound) throw PhoneCameraException(L.s(R.string.phone_camera_not_ready))
        val (bitmap, rotation, jpeg) = suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // Сначала декодирование, потом копия исходного JPEG (только для проверки EXIF в debug-логе).
                        val bitmap = image.toBitmap()
                        val original = if (BuildConfig.DEBUG) image.planes.firstOrNull()?.buffer?.toByteArray() else null
                        val result = Triple(bitmap, image.imageInfo.rotationDegrees, original)
                        image.close()
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(PhoneCameraException(L.s(R.string.phone_camera_failed)))
                    }
                },
            )
        }
        return withContext(Dispatchers.Default) { ImagePrep.fromBitmap(bitmap, rotation, ImageSource.PHONE_CAMERA.logName, jpeg) }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(remaining()).also { get(it) }
    }

    private companion object {
        const val TAG = "Image"
        const val FOCUS_TIMEOUT_MS = 1_500L
    }
}
