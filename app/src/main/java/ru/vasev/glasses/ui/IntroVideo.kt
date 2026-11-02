package ru.vasev.glasses.ui

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.R
import kotlin.math.max

/**
 * Стартовая видеозаставка (`res/raw/sputnik_splash.mp4`, 1080 × 2400, начинается и кончается чёрным) поверх
 * чёрного фона: «заполнить с обрезкой по центру», без звука, системные панели скрыты. Касание — пропустить;
 * ошибка декодера или ролик не начался за [MAX_MS] — сразу дальше, без сообщений. Приложение под ней уже
 * работает: заставка ничего не задерживает.
 */
@Composable
fun IntroVideo(onDone: () -> Unit) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    val state = remember { IntroState() }
    fun finish(reason: String) {
        if (state.finished) return
        state.finished = true
        Log.d(TAG, "Intro: done ($reason)")
        done()
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController((context as Activity).window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            state.release()
        }
    }
    // Страховка: что бы ни случилось с плеером, заставка не держит приложение дольше.
    LaunchedEffect(Unit) {
        delay(MAX_MS)
        finish("timeout")
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(Unit) { detectTapGestures { finish("tap") } },
        contentAlignment = Alignment.Center,
    ) {
        // Ролик 20:9: на экранах других пропорций обрезаются только края.
        val scale = max(maxWidth.value / VIDEO_WIDTH, maxHeight.value / VIDEO_HEIGHT)
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            state.play(ctx, Surface(texture), onEnd = { finish("end") }, onError = { finish("error") })
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                        // Ушли с экрана посреди ролика — при возврате его уже не показываем.
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            state.release()
                            finish("hidden")
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.requiredSize((VIDEO_WIDTH * scale).dp, (VIDEO_HEIGHT * scale).dp),
        )
    }
}

/** Плеер заставки: живёт, пока заставка на экране. */
private class IntroState {
    var finished = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    fun play(context: Context, target: Surface, onEnd: () -> Unit, onError: () -> Unit) {
        release()
        surface = target
        runCatching {
            val media = MediaPlayer()
            player = media
            context.resources.openRawResourceFd(R.raw.sputnik_splash).use { fd ->
                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            media.setSurface(target)
            media.setVolume(0f, 0f)
            media.setOnPreparedListener { it.start() }
            media.setOnCompletionListener { onEnd() }
            media.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Intro: player error what=$what extra=$extra")
                onError()
                true
            }
            media.prepareAsync()
        }.onFailure {
            Log.w(TAG, "Intro: cannot play ${it.javaClass.simpleName}")
            onError()
        }
    }

    fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }
}

private const val VIDEO_WIDTH = 1080f
private const val VIDEO_HEIGHT = 2400f
private const val MAX_MS = 6_000L
