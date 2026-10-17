package ru.vasev.glasses.llm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Выбор моделей по ролям и запасной вариант. По умолчанию — как было: Sonnet для ассистента, Haiku для перевода. */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    private val models = Role.entries.associateWith { role ->
        MutableStateFlow(ModelCatalog.byId(prefs.getString(modelKey(role), null)) ?: ModelCatalog.default(role))
    }

    private val _fallbackEnabled = MutableStateFlow(prefs.getBoolean(KEY_FALLBACK, true))
    private val _fallbackPhotos = MutableStateFlow(prefs.getBoolean(KEY_FALLBACK_PHOTOS, false))

    fun model(role: Role): StateFlow<ModelSpec> = models.getValue(role).asStateFlow()

    /** Повторять запрос через DeepSeek, если Claude недоступен. */
    val fallbackEnabled: StateFlow<Boolean> = _fallbackEnabled.asStateFlow()

    /** Разрешено ли при автоматической подмене отправлять фото в запасную модель. */
    val fallbackPhotos: StateFlow<Boolean> = _fallbackPhotos.asStateFlow()

    fun setModel(role: Role, model: ModelSpec) {
        prefs.edit().putString(modelKey(role), model.id).apply()
        models.getValue(role).value = model
    }

    fun setFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FALLBACK, enabled).apply()
        _fallbackEnabled.value = enabled
    }

    fun setFallbackPhotos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FALLBACK_PHOTOS, enabled).apply()
        _fallbackPhotos.value = enabled
    }

    private fun modelKey(role: Role) = "model_${role.name.lowercase()}"

    private companion object {
        const val KEY_FALLBACK = "fallback_enabled"
        const val KEY_FALLBACK_PHOTOS = "fallback_photos"
    }
}
