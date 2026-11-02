package ru.vasev.glasses.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import ru.vasev.glasses.R

/**
 * Цвета стиля «Классика» (утверждённый макет бокового меню, `docs/redesign/design/menu-classic.html`).
 * Тёмные значения — из задания; светлые выведены из них (на согласование).
 */
@Immutable
data class SputnikColors(
    /** Фон основного экрана. */
    val screen: Color,
    /** Фон бокового меню. */
    val menu: Color,
    /** Поверхности: поиск, круглые кнопки, чип модели. */
    val surface: Color,
    /** Карточки и пузыри на фоне экрана. */
    val card: Color,
    /** Выделенный чат. */
    val selected: Color,
    val divider: Color,
    val text: Color,
    val text2: Color,
    /** Иконка «чат с очков». */
    val iconMuted: Color,
    /** Непрочитано, точка оповещений. */
    val accent: Color,
    /** Включено: кнопка перевода. */
    val on: Color,
    /** Статусы модели: доступна / задержки / недоступна / нет ключа. */
    val ok: Color,
    val warn: Color,
    val err: Color,
    val off: Color,
) {
    companion object {
        val Dark = SputnikColors(
            screen = Color(0xFF000000),
            menu = Color(0xFF121214),
            surface = Color(0xFF26272C),
            card = Color(0xFF141518),
            selected = Color(0xFF232428),
            divider = Color(0xFF222327),
            text = Color(0xFFF2F2F3),
            text2 = Color(0xFF8E8F96),
            iconMuted = Color(0xFF6E6F76),
            accent = Color(0xFF3B82F6),
            on = Color(0xFF34D399),
            ok = Color(0xFF34D399),
            warn = Color(0xFFF5B14C),
            err = Color(0xFFF87171),
            off = Color(0xFF6E6F76),
        )

        val Light = SputnikColors(
            screen = Color(0xFFFFFFFF),
            menu = Color(0xFFF4F4F6),
            surface = Color(0xFFE9EAEE),
            card = Color(0xFFF4F4F6),
            selected = Color(0xFFE2E3E8),
            divider = Color(0xFFE3E4E8),
            text = Color(0xFF141416),
            text2 = Color(0xFF5F6068),
            iconMuted = Color(0xFF8A8B92),
            accent = Color(0xFF2563EB),
            on = Color(0xFF059669),
            ok = Color(0xFF059669),
            warn = Color(0xFFB45309),
            err = Color(0xFFDC2626),
            off = Color(0xFF8A8B92),
        )
    }
}

val LocalSputnikColors = staticCompositionLocalOf { SputnikColors.Dark }

/** Manrope (с кириллицей) — один переменный файл в APK, насыщенность задаётся осью `wght`. */
val Manrope = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun Typography.withFont(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private val SputnikTypography = Typography().withFont(Manrope)

/** Тёмная ли тема при выбранном режиме. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Тема «Классика»: плоские поверхности, Manrope, масштаб шрифта приложения [fontScalePercent]
 * (умножается на системный размер шрифта).
 */
@Composable
fun GlassesTheme(dark: Boolean = true, fontScalePercent: Int = 100, content: @Composable () -> Unit) {
    val colors = if (dark) SputnikColors.Dark else SputnikColors.Light
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * fontScalePercent / 100f)
    MaterialTheme(colorScheme = sputnikColorScheme(dark), typography = SputnikTypography) {
        CompositionLocalProvider(LocalSputnikColors provides colors, LocalDensity provides scaled, content = content)
    }
}

/** Цвета Material 3 из палитры «Классики»: старые экраны берут их через `MaterialTheme.colorScheme`. */
fun sputnikColorScheme(dark: Boolean): ColorScheme {
    val p = if (dark) SputnikColors.Dark else SputnikColors.Light
    return if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White,
            // Белый текст на #2563EB — 5.2:1 (на самом акценте #3B82F6 было бы 3.7:1).
            primaryContainer = Color(0xFF2563EB), onPrimaryContainer = Color.White,
            secondary = p.text2, onSecondary = p.screen,
            secondaryContainer = p.selected, onSecondaryContainer = p.text,
            tertiary = p.on, onTertiary = p.screen,
            tertiaryContainer = Color(0xFF15302A), onTertiaryContainer = Color(0xFFD1FAE5),
            error = p.err, onError = p.screen,
            errorContainer = Color(0xFF3B1517), onErrorContainer = Color(0xFFFECACA),
            background = p.screen, onBackground = p.text,
            surface = p.screen, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.text2,
            outline = Color(0xFF45464C), outlineVariant = p.divider,
            surfaceContainerLowest = p.screen, surfaceContainerLow = Color(0xFF0C0C0E),
            surfaceContainer = p.menu, surfaceContainerHigh = Color(0xFF1C1D21), surfaceContainerHighest = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White,
            primaryContainer = p.accent, onPrimaryContainer = Color.White,
            secondary = p.text2, onSecondary = p.screen,
            secondaryContainer = p.selected, onSecondaryContainer = p.text,
            tertiary = p.on, onTertiary = Color.White,
            tertiaryContainer = Color(0xFFD1FAE5), onTertiaryContainer = Color(0xFF064E3B),
            error = p.err, onError = Color.White,
            errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
            background = p.screen, onBackground = p.text,
            surface = p.screen, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.text2,
            outline = Color(0xFF8A8B92), outlineVariant = p.divider,
            surfaceContainerLowest = p.screen, surfaceContainerLow = Color(0xFFF9F9FA),
            surfaceContainer = p.menu, surfaceContainerHigh = Color(0xFFEEEFF2), surfaceContainerHighest = p.surface,
        )
    }
}
