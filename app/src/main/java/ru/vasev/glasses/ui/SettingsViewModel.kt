package ru.vasev.glasses.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ProviderId
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.security.ApiKeyException
import ru.vasev.glasses.security.ApiKeyInfo

/** Состояние ключа одного провайдера на экране. */
data class KeyState(
    /** Сохранённый ключ: только последние 4 символа. */
    val key: ApiKeyInfo? = null,
    /** Показывать поле ввода (ключа нет или нажато «Заменить ключ»). */
    val editing: Boolean = false,
    val confirmDelete: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as GlassesApp).apiKeys
    private val ai = (app as GlassesApp).aiSettings

    /** Провайдеры, для которых в каталоге есть модели (OpenAI появится, когда добавим его модели). */
    val providers: List<ProviderId> = ProviderId.entries.filter { p -> ModelCatalog.all.any { it.provider == p } }

    private val _keys = MutableStateFlow(providers.associateWith { KeyState(editing = !store.has(it)) })
    val keys: StateFlow<Map<ProviderId, KeyState>> = _keys.asStateFlow()

    val assistantModel: StateFlow<ModelSpec> = ai.model(Role.ASSISTANT)
    val translationModel: StateFlow<ModelSpec> = ai.model(Role.TRANSLATION)
    val fallbackEnabled: StateFlow<Boolean> = ai.fallbackEnabled
    val fallbackPhotos: StateFlow<Boolean> = ai.fallbackPhotos

    fun setModel(role: Role, model: ModelSpec) = ai.setModel(role, model)
    fun setFallbackEnabled(enabled: Boolean) = ai.setFallbackEnabled(enabled)
    fun setFallbackPhotos(enabled: Boolean) = ai.setFallbackPhotos(enabled)

    /** Обновить сведения о ключах. Расшифровка — только здесь, при открытом экране, и только ради последних 4 символов. */
    fun refresh() = viewModelScope.launch {
        for (provider in providers) {
            if (!store.has(provider)) {
                update(provider) { it.copy(key = null, editing = true) }
                continue
            }
            val key = try {
                withContext(Dispatchers.Default) { store.describe(provider) }
            } catch (e: ApiKeyException) {
                update(provider) { it.copy(key = null, editing = true, error = e.message) }
                continue
            }
            update(provider) { it.copy(key = key, editing = key == null) }
        }
    }

    fun save(provider: ProviderId, input: String) = viewModelScope.launch {
        update(provider) { it.copy(busy = true, error = null, message = null) }
        try {
            val key = withContext(Dispatchers.Default) {
                store.save(provider, input)
                store.describe(provider)
            }
            update(provider) { it.copy(key = key, editing = false, busy = false, message = "Ключ сохранён.") }
        } catch (e: ApiKeyException) {
            update(provider) { it.copy(busy = false, error = e.message) }
        } catch (e: Exception) {
            update(provider) { it.copy(busy = false, error = "Не удалось зашифровать ключ (${e.javaClass.simpleName}).") }
        }
    }

    fun startReplace(provider: ProviderId) =
        update(provider) { it.copy(editing = true, confirmDelete = false, error = null, message = null) }

    fun cancelReplace(provider: ProviderId) = update(provider) { it.copy(editing = it.key == null, error = null) }

    fun askDelete(provider: ProviderId) = update(provider) { it.copy(confirmDelete = true, message = null) }

    fun cancelDelete(provider: ProviderId) = update(provider) { it.copy(confirmDelete = false) }

    fun delete(provider: ProviderId) = viewModelScope.launch {
        withContext(Dispatchers.Default) { store.delete(provider) }
        update(provider) { KeyState(editing = true, message = "Ключ удалён.") }
    }

    private fun update(provider: ProviderId, change: (KeyState) -> KeyState) =
        _keys.update { all -> all + (provider to change(all.getValue(provider))) }
}
