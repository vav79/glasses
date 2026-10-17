package ru.vasev.glasses.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R

/**
 * Большая круглая кнопка микрофона — состояние видно издалека:
 * - покой: микрофон, нажатие — спросить;
 * - «слушаю»: ореол пульсирует по громкости голоса;
 * - «думаю»: бегущее кольцо вокруг кнопки;
 * - «говорю»: мягко «дышащий» ореол.
 * Пока что-то идёт, нажатие останавливает (иконка «стоп» в «думаю»).
 */
@Composable
fun MicButton(phase: Phase, level: Float, enabled: Boolean, onClick: () -> Unit, size: Dp = 104.dp) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (phase) {
            Phase.IDLE -> colors.primary
            Phase.LISTENING -> colors.error
            Phase.CAPTURING, Phase.THINKING -> colors.secondary
            Phase.SPEAKING -> colors.tertiary
        },
        label = "micColor",
    )
    val content = when (phase) {
        Phase.IDLE -> colors.onPrimary
        Phase.LISTENING -> colors.onError
        Phase.CAPTURING, Phase.THINKING -> colors.onSecondary
        Phase.SPEAKING -> colors.onTertiary
    }
    val icon = when (phase) {
        Phase.IDLE, Phase.LISTENING -> R.drawable.ic_sym_mic
        Phase.CAPTURING -> R.drawable.ic_sym_visibility
        Phase.THINKING -> R.drawable.ic_sym_stop
        Phase.SPEAKING -> R.drawable.ic_sym_volume_up
    }

    val transition = rememberInfiniteTransition(label = "mic")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    // Громкость сглаживается пружиной, чтобы ореол не дрожал.
    val voice by animateFloatAsState(if (phase == Phase.LISTENING) level else 0f, spring(stiffness = 300f), label = "voice")

    val haloScale = when (phase) {
        Phase.LISTENING -> 1.06f + voice * 0.30f + breath * 0.04f
        Phase.SPEAKING -> 1.06f + breath * 0.10f
        else -> 1f
    }
    val haloVisible = phase == Phase.LISTENING || phase == Phase.SPEAKING

    val description = if (phase == Phase.IDLE) "Спросить голосом" else "Остановить"
    val state = when (phase) {
        Phase.IDLE -> "готов"
        Phase.CAPTURING -> "снимаю кадр"
        Phase.LISTENING -> "слушаю"
        Phase.THINKING -> "думаю"
        Phase.SPEAKING -> "говорю"
    }

    Box(Modifier.size(size * 1.4f), contentAlignment = Alignment.Center) {
        if (haloVisible) {
            Box(
                Modifier
                    .size(size)
                    .scale(haloScale)
                    .graphicsLayer { alpha = 0.28f }
                    .clip(CircleShape)
                    .background(container),
            )
        }
        if (phase == Phase.THINKING || phase == Phase.CAPTURING) {
            CircularProgressIndicator(
                modifier = Modifier.size(size + 16.dp).graphicsLayer { rotationZ = spin },
                color = container,
                strokeWidth = 5.dp,
            )
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (enabled) container else colors.surfaceVariant)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics {
                    contentDescription = description
                    stateDescription = state
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (enabled) content else colors.onSurfaceVariant,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}
