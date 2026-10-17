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
import ru.vasev.glasses.security.ApiKeyException
import ru.vasev.glasses.security.ApiKeyInfo

data class SettingsUiState(
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

    private val _state = MutableStateFlow(SettingsUiState(editing = !store.hasKey.value))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Обновить сведения о ключе. Расшифровка — только здесь, при открытом экране, и только ради последних 4 символов. */
    fun refresh() = viewModelScope.launch {
        if (!store.hasKey.value) {
            _state.update { it.copy(key = null, editing = true) }
            return@launch
        }
        val key = try {
            withContext(Dispatchers.Default) { store.describe() }
        } catch (e: ApiKeyException) {
            _state.update { it.copy(key = null, editing = true, error = e.message) }
            return@launch
        }
        _state.update { it.copy(key = key, editing = key == null) }
    }

    fun save(input: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null, message = null) }
        try {
            val key = withContext(Dispatchers.Default) {
                store.save(input)
                store.describe()
            }
            _state.update { it.copy(key = key, editing = false, busy = false, message = "Ключ сохранён.") }
        } catch (e: ApiKeyException) {
            _state.update { it.copy(busy = false, error = e.message) }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = "Не удалось зашифровать ключ (${e.javaClass.simpleName}).") }
        }
    }

    fun startReplace() = _state.update { it.copy(editing = true, confirmDelete = false, error = null, message = null) }

    fun cancelReplace() = _state.update { it.copy(editing = it.key == null, error = null) }

    fun askDelete() = _state.update { it.copy(confirmDelete = true, message = null) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun delete() = viewModelScope.launch {
        withContext(Dispatchers.Default) { store.delete() }
        _state.update { SettingsUiState(editing = true, message = "Ключ удалён.") }
    }
}
