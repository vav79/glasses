package ru.vasev.glasses.glasses

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.R
import java.io.File

/** Симулятор Ray-Ban Meta на базе MockDeviceKit: регистрация, разрешения и тестовая картинка. */
class MockGlassesController(private val context: Context) {

    private val kit get() = MockDeviceKit.getInstance(context)

    suspend fun enable() {
        if (!kit.isEnabled) {
            kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        }
        val glasses = kit.pairedDevices.filterIsInstance<MockGlasses>().firstOrNull()
            ?: kit.pairGlasses(GlassesModel.RAYBAN_META).let { result ->
                result.getOrNull() ?: run {
                    Log.e(TAG, "Mock pairing failed: ${result.errorOrNull()?.description}")
                    return
                }
            }
        glasses.powerOn()
        glasses.unfold()
        glasses.don()
        // Заряд для индикатора очков (симулятор сам его не задаёт).
        glasses.setBatteryLevel(MOCK_BATTERY)

        // Стрим симулятора требует видеоисточник, поэтому тестовая картинка подаётся и как видео, и как снимок.
        runCatching { testVideo() }
            .onSuccess { glasses.services.camera.setCameraFeed(Uri.fromFile(it)) }
            .onFailure { Log.e(TAG, "Mock video encoding failed", it) }
        glasses.services.camera.setCapturedImage(testImageUri())
    }

    fun disable() {
        if (kit.isEnabled) kit.disable()
    }

    private suspend fun testVideo(): File = withContext(Dispatchers.Default) {
        val file = File(context.cacheDir, "mock_view_v1.mp4")
        if (file.exists()) return@withContext file
        val bitmap = BitmapFactory.decodeResource(context.resources, R.raw.mock_view)
        MockVideoEncoder.encode(bitmap, file)
    }

    private fun testImageUri(): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath("raw")
        .appendPath(context.resources.getResourceEntryName(R.raw.mock_view))
        .build()

    private companion object {
        const val MOCK_BATTERY = 76
    }
}
