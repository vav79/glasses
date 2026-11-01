package ru.vasev.glasses.ui

import ru.vasev.glasses.speech.GestureState
import ru.vasev.glasses.speech.Tap
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.rates.Rates
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageChoice
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ProviderId
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.nav.NavigatorChoice
import ru.vasev.glasses.profile.UserRole
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

    private val _language = MutableStateFlow(LanguageSettings.choice())
    val language: StateFlow<LanguageChoice> = _language.asStateFlow()

    /** Язык сменился не кнопками (система, adb) — подсветка выбора следует за ним. */
    fun syncLanguage() {
        _language.value = LanguageSettings.choice()
    }

    fun setLanguage(choice: LanguageChoice) {
        _language.value = choice
        LanguageSettings.set(choice)
    }

    private val profile = (app as GlassesApp).profile
    val role: StateFlow<UserRole> = profile.role
    val showAdvanced: StateFlow<Boolean> = profile.showAdvanced
    val journalTexts: StateFlow<Boolean> = profile.journalTexts

    fun setRole(role: UserRole) = profile.setRole(role)
    fun setShowAdvanced(show: Boolean) = profile.setShowAdvanced(show)
    fun setJournalTexts(enabled: Boolean) = profile.setJournalTexts(enabled)

    /** Голосовое «стоп» во время ответа и есть ли сейчас гарнитура с микрофоном. */
    val voiceStop: StateFlow<Boolean> = profile.voiceStop
    val headsetMic: StateFlow<Boolean> = (app as GlassesApp).audioRouter.headset
        .map { it?.mic != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (app as GlassesApp).audioRouter.headset.value?.mic != null)

    fun setVoiceStop(enabled: Boolean) = profile.setVoiceStop(enabled)

    /** «Жест на дужке»: включён ли, какой жест и ловятся ли касания сейчас. */
    private val gestureSettings = (app as GlassesApp).gestureSettings
    val gestureEnabled: StateFlow<Boolean> = gestureSettings.enabled
    val gestureTap: StateFlow<Tap> = gestureSettings.tap
    val gestureState: StateFlow<GestureState> = (app as GlassesApp).gestures.state

    fun setGestureEnabled(enabled: Boolean) = gestureSettings.setEnabled(enabled)
    fun setGestureTap(tap: Tap) = gestureSettings.setTap(tap)

    /** «Меньше прозрачности» — стекло плотнее. */
    val lessTransparency: StateFlow<Boolean> = profile.lessTransparency

    fun setLessTransparency(enabled: Boolean) = profile.setLessTransparency(enabled)

    /** «Моя валюта» и последние сохранённые курсы ЦБ. */
    private val currencySettings = (app as GlassesApp).currency
    val currency: StateFlow<String> = currencySettings.currency
    val rates: StateFlow<Rates?> = (app as GlassesApp).rates.cached

    fun setCurrency(code: String) = currencySettings.set(code)

    private val navigatorSettings = (app as GlassesApp).navigator
    val navigator: StateFlow<NavigatorChoice> = navigatorSettings.choice

    fun setNavigator(choice: NavigatorChoice) = navigatorSettings.setChoice(choice)

    val taxiLanguage: StateFlow<String?> = navigatorSettings.taxiLanguage

    /** Сменили язык адреса для такси — переводим адреса моих мест заново, пока есть сеть. */
    fun setTaxiLanguage(language: String?) {
        navigatorSettings.setTaxiLanguage(language)
        (getApplication<GlassesApp>()).taxiAddresses.refreshMissing()
    }

    fun setModel(role: Role, model: ModelSpec) = ai.setModel(role, model)
    fun setFallbackEnabled(enabled: Boolean) = ai.setFallbackEnabled(enabled)
    fun setFallbackPhotos(enabled: Boolean) = ai.setFallbackPhotos(enabled)

    /** Обновить сведения о ключах. Расшифровка — только здесь, при открытом экране, и только ради последних 4 символов. */
    fun refresh() = viewModelScope.launch {
        syncLanguage()
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
            update(provider) { it.copy(key = key, editing = false, busy = false, message = L.s(R.string.key_saved_message)) }
        } catch (e: ApiKeyException) {
            update(provider) { it.copy(busy = false, error = e.message) }
        } catch (e: Exception) {
            update(provider) { it.copy(busy = false, error = L.s(R.string.key_encrypt_failed, e.javaClass.simpleName)) }
        }
    }

    fun startReplace(provider: ProviderId) =
        update(provider) { it.copy(editing = true, confirmDelete = false, error = null, message = null) }

    fun cancelReplace(provider: ProviderId) = update(provider) { it.copy(editing = it.key == null, error = null) }

    fun askDelete(provider: ProviderId) = update(provider) { it.copy(confirmDelete = true, message = null) }

    fun cancelDelete(provider: ProviderId) = update(provider) { it.copy(confirmDelete = false) }

    fun delete(provider: ProviderId) = viewModelScope.launch {
        withContext(Dispatchers.Default) { store.delete(provider) }
        update(provider) { KeyState(editing = true, message = L.s(R.string.key_deleted_message)) }
    }

    private fun update(provider: ProviderId, change: (KeyState) -> KeyState) =
        _keys.update { all -> all + (provider to change(all.getValue(provider))) }
}
