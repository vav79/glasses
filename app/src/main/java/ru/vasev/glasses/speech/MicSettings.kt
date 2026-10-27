package ru.vasev.glasses.speech

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.vasev.glasses.R

/** Чем слушать мой голос (ассистент и «Я отвечаю»). Собеседника всегда слушает микрофон телефона. */
enum class MicChoice(@StringRes val titleRes: Int, val available: Boolean = true) {
    /** Очки или другая гарнитура, если подключены и включили микрофон (очки — в приоритете), иначе телефон. */
    AUTO(R.string.mic_auto),

    /**
     * Только микрофон очков — они подключаются по Bluetooth как гарнитура (HFP), распознаёт Google на телефоне.
     * DAT Speech не подошёл: русский не распознаёт. Очков нет — телефон.
     */
    GLASSES(R.string.mic_glasses),

    /** Гарнитура (очки — в приоритете); нет её — телефон (как «Авто», но выбор виден явно). */
    HEADSET(R.string.mic_headset),
    PHONE(R.string.mic_phone),
    ;

    /** Можно ли брать микрофон гарнитуры. */
    val allowsHeadset: Boolean get() = this != PHONE
}

class MicSettings(context: Context) {

    private val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(
        MicChoice.entries.firstOrNull { it.name == prefs.getString(KEY, null) && it.available } ?: MicChoice.AUTO,
    )
    val choice: StateFlow<MicChoice> = _choice.asStateFlow()

    fun set(choice: MicChoice) {
        if (!choice.available) return
        prefs.edit().putString(KEY, choice.name).apply()
        _choice.value = choice
    }

    private companion object {
        const val KEY = "mic"
    }
}
