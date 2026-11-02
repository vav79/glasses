package ru.vasev.glasses.translate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Чей микрофон слушает собеседника. */
enum class PartnerMic { PHONE, GLASSES }

/**
 * Настройки умения «Переводчик» (SharedPreferences `translate`, там же язык собеседника) и включён ли перевод
 * кнопкой в шапке. Собеседника по умолчанию слушает телефон: проба 2026-11-02 — микрофон очков собеседника
 * на 1–2 м не распознал, телефон — 100 % слов.
 */
class TranslatorSettings(context: Context) {

    private val prefs = context.getSharedPreferences("translate", Context.MODE_PRIVATE)

    private val _partnerMic = MutableStateFlow(PartnerMic.entries.firstOrNull { it.name == prefs.getString(KEY_MIC, null) } ?: PartnerMic.PHONE)
    val partnerMic: StateFlow<PartnerMic> = _partnerMic.asStateFlow()

    private val _speakMine = MutableStateFlow(prefs.getBoolean(KEY_SPEAK_MINE, false))

    /** Озвучивать перевод моей фразы собеседнику через динамик телефона. По умолчанию — только текстом («Показать»). */
    val speakMine: StateFlow<Boolean> = _speakMine.asStateFlow()

    private val _headerOn = MutableStateFlow(false)

    /** Перевод включён кнопкой в шапке (к любому профилю). Не сохраняется: при запуске выключен. */
    val headerOn: StateFlow<Boolean> = _headerOn.asStateFlow()

    private val _profile = MutableStateFlow(prefs.getBoolean(KEY_PROFILE, false))

    /** Выбран профиль «Переводчик» в чипе: перевод включён, кнопка в шапке скрыта. */
    val profile: StateFlow<Boolean> = _profile.asStateFlow()

    fun setPartnerMic(mic: PartnerMic) {
        prefs.edit().putString(KEY_MIC, mic.name).apply()
        _partnerMic.value = mic
    }

    fun setSpeakMine(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_MINE, enabled).apply()
        _speakMine.value = enabled
    }

    fun setHeaderOn(on: Boolean) {
        _headerOn.value = on
    }

    fun setProfile(on: Boolean) {
        prefs.edit().putBoolean(KEY_PROFILE, on).apply()
        _profile.value = on
        if (on) _headerOn.value = false
    }

    private companion object {
        const val KEY_MIC = "partner_mic"
        const val KEY_SPEAK_MINE = "speak_mine"
        const val KEY_PROFILE = "profile"
    }
}
