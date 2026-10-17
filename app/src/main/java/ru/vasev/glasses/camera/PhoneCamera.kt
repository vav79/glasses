package ru.vasev.glasses.camera

import android.content.Context
import ru.vasev.glasses.BuildConfig
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
                Log.d(TAG, "камера телефона включена")
            } catch (e: Exception) {
                bound = false
                Log.w(TAG, "не удалось включить камеру телефона: ${e.javaClass.simpleName}")
            }
        }, mainExecutor)
    }

    fun unbind() {
        provider?.unbindAll()
        if (bound) Log.d(TAG, "камера телефона выключена")
        bound = false
    }

    /** Снимок с учётом поворота, уменьшенный и без метаданных. */
    suspend fun capture(): ByteArray {
        if (!bound) throw PhoneCameraException("Камера телефона не готова. Откройте вкладку «Ассистент» и разрешите доступ к камере.")
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
                        if (cont.isActive) cont.resumeWithException(PhoneCameraException("Не удалось сделать снимок камерой телефона."))
                    }
                },
            )
        }
        return withContext(Dispatchers.Default) { ImagePrep.fromBitmap(bitmap, rotation, "телефон", jpeg) }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(remaining()).also { get(it) }
    }

    private companion object {
        const val TAG = "Image"
    }
}
