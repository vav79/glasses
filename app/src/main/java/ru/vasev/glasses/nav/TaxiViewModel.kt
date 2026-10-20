package ru.vasev.glasses.nav

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.VoiceStatus
import java.util.Locale

/**
 * Экран «Адрес для таксиста». [local] — перевод (из моих мест — сразу, иначе считается; без сети — нет);
 * [homeConsent] — адрес «Дом» переводится моделью только по явной просьбе: показать кнопку.
 */
data class TaxiCard(
    val target: Target,
    val plusCode: String,
    val apps: List<TaxiApp>,
    val local: LocalAddress? = null,
    val loading: Boolean = false,
    val homeConsent: Boolean = false,
    val offline: Boolean = false,
)

/**
 * «Адрес для таксиста»: для цели из карточки, моего места или голосовой команды ([NavSession.taxi]) — адрес
 * на местном языке, Plus Code, «Произнести» в динамик телефона, приложения такси.
 */
class TaxiViewModel(app: Application) : AndroidViewModel(app) {

    private val g = app as GlassesApp
    private val session = g.navSession
    private val store = g.places

    private val _card = MutableStateFlow<TaxiCard?>(null)
    val card: StateFlow<TaxiCard?> = _card.asStateFlow()

    private var translation: Job? = null

    init {
        viewModelScope.launch {
            var shown: Target? = null
            session.taxi.collect { target ->
                if (target == shown) return@collect
                shown = target
                if (target == null) close() else open(target)
            }
        }
    }

    private fun open(target: Target) {
        translation?.cancel()
        val place = target.placeId?.let(store::byId)
        val stored = place?.let { p ->
            p.localAddress?.takeIf { p.localLanguage == g.taxiAddresses.languageFor(p.country) }
                ?.let { LocalAddress(p.localName, it, p.localLanguage!!, p.localBy) }
        }
        val apps = TaxiApp.entries.filter { session.installed(it.packageName) }
        val online = g.llm.isOnline()
        _card.value = TaxiCard(target, PlusCode.encode(target.point), apps, stored, loading = stored == null && online, offline = !online)
        if (stored != null || !online) return
        translation = viewModelScope.launch {
            val local = if (place != null) g.taxiAddresses.ensure(place) else g.taxiAddresses.forTarget(target)
            val consent = local == null && place?.kind == PlaceKind.HOME
            _card.update { it?.copy(local = local, loading = false, homeConsent = consent) }
        }
    }

    private fun close() {
        translation?.cancel()
        _card.value = null
    }

    fun closeTaxi() = session.closeTaxi()

    /** «Перевести через ИИ» для «Дома» — явная просьба: только тогда адрес дома уходит модели. */
    fun translateHomeWithAi() {
        val card = _card.value ?: return
        val place = card.target.placeId?.let(store::byId) ?: return
        _card.value = card.copy(loading = true, homeConsent = false)
        translation = viewModelScope.launch {
            val local = g.taxiAddresses.ensure(place, force = true, allowHomeModel = true)
            _card.update { it?.copy(local = local, loading = false) }
        }
    }

    fun sameAsApp(language: String) = g.taxiAddresses.sameAsApp(language)

    /** Произнести адрес на местном языке — в динамик телефона, для водителя. */
    fun speakTaxi() {
        val local = _card.value?.local ?: return
        viewModelScope.launch {
            val locale = Locale.forLanguageTag(local.language)
            if (g.audioOutput.voiceStatus(locale) != VoiceStatus.READY) {
                session.notify(L.s(R.string.taxi_no_voice))
                return@launch
            }
            runCatching { g.audioOutput.speak(listOfNotNull(local.name, local.address).joinToString(". "), locale, AudioChannel.PARTNER) }
        }
    }

    /** Приложение такси: с пунктом назначения, если у него есть документированная ссылка, иначе просто открыть. */
    fun openTaxiApp(app: TaxiApp) {
        val target = _card.value?.target ?: return
        val link = TaxiLinks.destination(app, target.point, target.label)
        Log.d(NavSession.TAG, "taxi app=${app.name} with_destination=${link != null}")
        if (link != null) session.open(MapsLink(link, app.packageName)) else session.launch(app.packageName)
    }

    suspend fun placePhoto(place: Place, maxSide: Int = 1024) = store.photo(place, maxSide)
}
