package ru.vasev.glasses.ui.glass

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Вид стеклянной кнопки: основная — подложка акцентного цвета, обычная — светлое стекло. */
enum class GlassButtonKind { PRIMARY, NORMAL, TONAL_TERTIARY, ERROR }

@Composable
private fun colorsFor(kind: GlassButtonKind): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        GlassButtonKind.PRIMARY -> c.primaryContainer to c.onPrimaryContainer
        GlassButtonKind.NORMAL -> c.surface to c.onSurface
        GlassButtonKind.TONAL_TERTIARY -> c.tertiaryContainer to c.onTertiaryContainer
        GlassButtonKind.ERROR -> c.errorContainer to c.onErrorContainer
    }
}

/**
 * Кнопка-пилюля из стекла (зона нажатия ≥ 48 dp). Иконка рядом с текстом — декоративная.
 * [blur] — кнопка лежит поверх прокручиваемого содержимого.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    kind: GlassButtonKind = GlassButtonKind.NORMAL,
    enabled: Boolean = true,
    blur: Boolean = false,
    minHeight: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
) {
    val (tint, content) = colorsFor(kind)
    Row(
        modifier
            .defaultMinSize(minHeight = minHeight)
            .glass(GlassShapes.pill, tint, content, blur = blur)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, color = content, style = textStyle, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** Круглая стеклянная кнопка с иконкой; [description] — подпись для TalkBack. */
@Composable
fun GlassIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    enabled: Boolean = true,
    blur: Boolean = false,
    kind: GlassButtonKind = GlassButtonKind.NORMAL,
) {
    val (tint, content) = colorsFor(kind)
    Box(
        modifier
            .size(size)
            .glass(GlassShapes.pill, tint, content, blur = blur)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = content, modifier = Modifier.size(size * 0.46f))
    }
}

/** Стеклянная карточка с отступами внутри. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface,
    text: Color = MaterialTheme.colorScheme.onSurface,
    blur: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides text) {
        Column(
            modifier.glass(GlassShapes.card, tint, text, blur = blur).padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

private const val DISABLED_ALPHA = 0.45f
