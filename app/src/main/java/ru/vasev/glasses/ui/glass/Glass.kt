package ru.vasev.glasses.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.ui.theme.LocalSputnikColors

/*
 * Бывшее «лёгкое стекло» — теперь плоские поверхности стиля «Классика» (В8: стекло убрано целиком).
 * Имена оставлены, чтобы экраны переходили на новый стиль без переписывания; переименование — на этапе уборки.
 */

/** Формы: пилюли для кнопок и чипов, скругление 12 dp — строки, 20 dp — карточки и пузыри. */
object GlassShapes {
    val pill = RoundedCornerShape(percent = 50)
    val card = RoundedCornerShape(20.dp)
}

/**
 * Плоская подложка: нейтральный [tint] (= `colorScheme.surface`) — поверхность «Классики»
 * (у пилюль — #26272C, у карточек — чуть темнее), цветной — как есть. Без размытия, обводки и тени.
 * [text], [blur] и [elevation] остались от стекла и не используются.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    tint: Color = MaterialTheme.colorScheme.surface,
    text: Color = MaterialTheme.colorScheme.onSurface,
    blur: Boolean = false,
    elevation: Dp = 0.dp,
): Modifier {
    val p = LocalSputnikColors.current
    val fill = if (tint == MaterialTheme.colorScheme.surface) (if (shape == GlassShapes.pill) p.surface else p.card) else tint
    return this.clip(shape).background(fill)
}

/** Акцентный цвет на поверхности: в «Классике» палитра постоянная и контраст проверен заранее — как есть. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun glassAccent(
    accent: Color = MaterialTheme.colorScheme.primary,
    tint: Color = MaterialTheme.colorScheme.surface,
    text: Color = MaterialTheme.colorScheme.onSurface,
    overContent: Boolean = false,
): Color = accent

/** Корень экрана: сплошной фон и основной цвет текста (без него текст был бы чёрным в тёмной теме). */
@Composable
fun GlassRoot(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize().background(LocalSputnikColors.current.screen)) { content() }
    }
}

/** Было: источник размытия. Размытия больше нет — ничего не делает. */
fun Modifier.glassContentSource(): Modifier = this

/** Второстепенный текст прямо на фоне экрана. */
@Composable
fun glassBackgroundText(color: Color = MaterialTheme.colorScheme.onSurfaceVariant): Color = color
