package ru.vasev.glasses.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * «Жест на дужке» (Настройки → «Очки»): включён ли и какой жест начинает вопрос. По умолчанию выключено,
 * жест — двойное касание: очки носят весь день и поправляют, одиночное касание срабатывало бы случайно.
 */
class GestureSettings(context: Context) {

    private val prefs = context.getSharedPreferences("gestures", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _tap = MutableStateFlow(Tap.entries.firstOrNull { it.name == prefs.getString(KEY_TAP, null) } ?: Tap.DOUBLE)
    val tap: StateFlow<Tap> = _tap.asStateFlow()

    /** Жест, начинающий вопрос; null — жесты выключены. */
    val chosen: Tap? get() = if (_enabled.value) _tap.value else null

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    fun setTap(tap: Tap) {
        prefs.edit().putString(KEY_TAP, tap.name).apply()
        _tap.value = tap
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TAP = "tap"
    }
}
