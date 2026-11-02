package ru.vasev.glasses.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Пользовательское соглашение: на телефоне хранятся принятая редакция и дата (SharedPreferences `agreement`).
 * Вышла новая редакция ([CURRENT] изменилась) — при запуске экран соглашения показывается снова.
 */
class AgreementSettings(context: Context) {

    private val prefs = context.getSharedPreferences("agreement", Context.MODE_PRIVATE)

    private val _acceptedVersion = MutableStateFlow(prefs.getString(KEY_VERSION, null))
    val acceptedVersion: StateFlow<String?> = _acceptedVersion.asStateFlow()

    /** Когда принято (мс, часы телефона); 0 — не принято. */
    val acceptedAt: Long get() = prefs.getLong(KEY_AT, 0L)

    fun accept() {
        prefs.edit().putString(KEY_VERSION, CURRENT).putLong(KEY_AT, System.currentTimeMillis()).apply()
        _acceptedVersion.value = CURRENT
    }

    /** Только debug: снова показать соглашение. */
    fun reset() {
        prefs.edit().clear().apply()
        _acceptedVersion.value = null
    }

    companion object {
        /** Редакция текста `res/raw/agreement.txt`; поменяли текст — поднять номер. */
        const val CURRENT = "1.0"
        private const val KEY_VERSION = "version"
        private const val KEY_AT = "accepted_at"

        fun isCurrent(version: String?): Boolean = version == CURRENT
    }
}
