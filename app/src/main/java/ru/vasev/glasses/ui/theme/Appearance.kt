package ru.vasev.glasses.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Тема приложения: по умолчанию тёмная (стиль «Классика»), светлая или как в системе. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/**
 * Оформление (Настройки → Тема, Размер шрифта): SharedPreferences `appearance`.
 * Масштаб шрифта умножается на системный и действует на всё приложение.
 */
class AppearanceSettings(context: Context) {

    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.DARK)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _fontScale = MutableStateFlow(prefs.getInt(KEY_FONT, 100).takeIf { it in FONT_SCALES } ?: 100)

    /** Размер шрифта в процентах: [FONT_SCALES]. */
    val fontScale: StateFlow<Int> = _fontScale.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    fun setFontScale(percent: Int) {
        if (percent !in FONT_SCALES) return
        prefs.edit().putInt(KEY_FONT, percent).apply()
        _fontScale.value = percent
    }

    companion object {
        val FONT_SCALES = listOf(80, 90, 100, 110, 120)
        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font_scale"
    }
}
