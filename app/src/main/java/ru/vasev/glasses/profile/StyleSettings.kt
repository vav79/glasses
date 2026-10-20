package ru.vasev.glasses.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** «Стиль общения» — SharedPreferences `style`, только на телефоне. */
class StyleSettings(context: Context) {

    private val prefs = context.getSharedPreferences("style", Context.MODE_PRIVATE)

    private val _current = MutableStateFlow(load())
    val current: StateFlow<CommunicationStyle> = _current.asStateFlow()

    private val _genderHintShown = MutableStateFlow(prefs.getBoolean(KEY_GENDER_HINT, false))

    /** Подсказка в «Переводе» «укажите пол» уже показана — второй раз не показываем. */
    val genderHintShown: StateFlow<Boolean> = _genderHintShown.asStateFlow()

    fun save(style: CommunicationStyle) {
        val clean = style.copy(customPrompt = style.customPrompt.trim().take(CommunicationStyle.MAX_CUSTOM))
        prefs.edit()
            .putString(KEY_ANSWER, clean.answer.name)
            .putString(KEY_CUSTOM, clean.customPrompt)
            .putString(KEY_FORMALITY, clean.formality.name)
            .putString(KEY_GENDER, clean.gender.name)
            .putString(KEY_PACE, clean.pace.name)
            .apply()
        _current.value = clean
    }

    fun markGenderHintShown() {
        prefs.edit().putBoolean(KEY_GENDER_HINT, true).apply()
        _genderHintShown.value = true
    }

    private fun load() = CommunicationStyle(
        answer = enumOr(KEY_ANSWER, AnswerStyle.BRIEF),
        customPrompt = prefs.getString(KEY_CUSTOM, "").orEmpty(),
        formality = enumOr(KEY_FORMALITY, Formality.POLITE),
        gender = enumOr(KEY_GENDER, SpeakerGender.UNSET),
        pace = enumOr(KEY_PACE, SpeechPace.NORMAL),
    )

    private inline fun <reified T : Enum<T>> enumOr(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: default

    private companion object {
        const val KEY_ANSWER = "answer"
        const val KEY_CUSTOM = "custom_prompt"
        const val KEY_FORMALITY = "formality"
        const val KEY_GENDER = "gender"
        const val KEY_PACE = "pace"
        const val KEY_GENDER_HINT = "gender_hint_shown"
    }
}
