package ru.vasev.glasses.ui

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass

/**
 * Кнопка микрофона — большая стеклянная капля; состояние видно издалека по свечению внутри:
 * - покой: слабое свечение акцентного цвета, нажатие — спросить;
 * - «слушаю»: свечение пульсирует по громкости голоса;
 * - «думаю» (и «снимаю»): по капле медленно бежит блик;
 * - «говорю»: мягко «дышит».
 * Пока идёт ответ, нажатие перебивает его и слушает новый вопрос; пока снимаем или слушаем — останавливает.
 * При системном «Убрать анимацию» движения нет — состояние видно по цвету свечения и иконке.
 */
@Composable
fun MicButton(phase: Phase, level: Float, enabled: Boolean, onClick: () -> Unit, diameter: Dp = 112.dp) {
    val colors = MaterialTheme.colorScheme
    val glow by animateColorAsState(
        when (phase) {
            Phase.IDLE -> colors.primary
            Phase.LISTENING -> colors.error
            Phase.CAPTURING, Phase.THINKING -> colors.tertiary
            Phase.SPEAKING -> colors.secondary
        },
        label = "micGlow",
    )
    val icon = when (phase) {
        Phase.IDLE, Phase.LISTENING -> R.drawable.ic_sym_mic
        Phase.CAPTURING -> R.drawable.ic_sym_visibility
        Phase.THINKING -> R.drawable.ic_sym_stop
        Phase.SPEAKING -> R.drawable.ic_sym_volume_up
    }

    val motion = ValueAnimator.areAnimatorsEnabled()
    val transition = rememberInfiniteTransition(label = "mic")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "spin")
    // Громкость сглаживается пружиной, чтобы свечение не дрожало.
    val voice by animateFloatAsState(if (phase == Phase.LISTENING) level else 0f, spring(stiffness = 300f), label = "voice")

    val b = if (motion) breath else 0.5f
    val strength = when (phase) {
        Phase.IDLE -> 0.22f
        Phase.LISTENING -> 0.45f + 0.4f * voice + 0.1f * b
        Phase.CAPTURING, Phase.THINKING -> 0.4f
        Phase.SPEAKING -> 0.35f + 0.3f * b
    }
    val radius = when (phase) {
        Phase.LISTENING -> 0.42f + 0.25f * voice
        Phase.SPEAKING -> 0.4f + 0.12f * b
        else -> 0.45f
    }
    val dropScale = if (motion && phase == Phase.LISTENING) 1f + 0.06f * voice else 1f

    val description = stringResource(
        when {
            phase == Phase.IDLE -> R.string.mic_ask
            phase.interruptible -> R.string.mic_interrupt
            else -> R.string.mic_stop
        },
    )
    val state = when (phase) {
        Phase.IDLE -> R.string.mic_state_ready
        Phase.CAPTURING -> R.string.mic_state_capturing
        Phase.LISTENING -> R.string.mic_state_listening
        Phase.THINKING -> R.string.mic_state_thinking
        Phase.SPEAKING -> R.string.mic_state_speaking
    }.let { stringResource(it) }

    Box(
        Modifier
            .size(diameter)
            .scale(dropScale)
            .glass(GlassShapes.pill, tint = colors.primaryContainer, text = colors.onPrimaryContainer, blur = true, elevation = 12.dp)
            .drawBehind {
                // Свечение внутри капли.
                drawCircle(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = strength.coerceAtMost(0.85f)), glow.copy(alpha = 0f)),
                        center = center,
                        radius = size.minDimension * radius,
                    ),
                )
                // «Думаю»: по краю капли бежит светлый блик.
                if (phase == Phase.THINKING || phase == Phase.CAPTURING) {
                    rotate(if (motion) spin else 45f) {
                        drawCircle(
                            Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.15f to Color.White.copy(alpha = 0.55f),
                                0.3f to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                            radius = size.minDimension / 2f,
                        )
                    }
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(diameter * 0.4f),
        )
    }
}
