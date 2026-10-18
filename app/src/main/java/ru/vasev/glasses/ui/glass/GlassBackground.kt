package ru.vasev.glasses.ui.glass

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import ru.vasev.glasses.ui.glass.GlassBackgroundPalette.Companion.fromScheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Цвета фона: основа и три мягких пятна из палитры обоев (Material You). */
data class GlassBackgroundPalette(val base: Color, val blobs: List<Color>) {

    /** Все цвета, которые могут оказаться позади стекла, — для расчёта контраста. */
    val all: List<Color> get() = listOf(base) + blobs.map { it.compositeOver(base) }

    companion object {
        @Composable
        fun fromScheme(): GlassBackgroundPalette {
            val c = MaterialTheme.colorScheme
            // В светлой теме контейнерные цвета обоев почти белые — стекло на них не видно; берём сами
            // акцентные цвета, разбавленные фоном. В тёмной контейнеры и так достаточно насыщенные.
            val light = c.surface.luminance() > 0.5f
            val blobs = if (light) {
                listOf(c.primary.copy(alpha = 0.32f), c.tertiary.copy(alpha = 0.30f), c.secondary.copy(alpha = 0.24f))
                    .map { it.compositeOver(c.surface) }
            } else {
                listOf(c.primaryContainer, c.tertiaryContainer, c.secondaryContainer)
            }
            return GlassBackgroundPalette(base = c.surface, blobs = blobs)
        }
    }
}

/**
 * Мягкий градиент из цветов обоев с медленным движением пятен (круг за 50–150 с — движение заметно
 * только боковым зрением). Рисуется в фазе отрисовки, без перекомпоновки. [animate] = false — фон застывает.
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    animate: Boolean,
    palette: GlassBackgroundPalette = fromScheme(),
    content: @Composable () -> Unit = {},
) {
    val time: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "glass-bg").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "glass-bg-time",
        )
    } else {
        remember { mutableStateOf(STILL_TIME) }
    }
    Box(
        modifier.drawBehind {
            drawRect(palette.base)
            val t = time.value * 2 * PI
            palette.blobs.forEachIndexed { i, color ->
                // Каждое пятно — по своей фигуре Лиссажу; разные фазы, чтобы пятна не ходили строем.
                val k = i + 1
                val cx = size.width * (0.5f + 0.38f * cos(t * SPEEDS[i] + k * 1.7).toFloat())
                val cy = size.height * (0.5f + 0.34f * sin(t * SPEEDS[(i + 1) % 3] + k * 2.3).toFloat())
                val radius = size.maxDimension * (0.55f + 0.08f * sin(t * 2 + k).toFloat())
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                )
            }
        },
    ) { content() }
}

/**
 * Можно ли двигать фон: нет энергосбережения и в системе не включено «Убрать анимацию».
 * Режим перевода и прочие условия экрана вызывающий учитывает сам.
 */
@Composable
fun rememberBackgroundMotionAllowed(): Boolean {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val saver = remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                saver.value = power.isPowerSaveMode
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    // «Убрать анимацию» обнуляет масштаб длительности анимаций — тогда ValueAnimator отключены.
    val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
    return !saver.value && animatorsEnabled
}

private const val PERIOD_MS = 150_000
private const val STILL_TIME = 0.18f
// Целые кратные: к концу цикла каждое пятно возвращается в начало, и перезапуск анимации не даёт скачка.
private val SPEEDS = listOf(1.0, 2.0, 3.0)
