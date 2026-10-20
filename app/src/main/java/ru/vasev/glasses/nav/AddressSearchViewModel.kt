package ru.vasev.glasses.nav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.speech.SpeechException
import java.util.Locale

/** Куда пойдёт подтверждённый адрес: стать целью или адресом моего места. */
sealed interface AddressPurpose {
    data object Destination : AddressPurpose
    data class ForPlace(val edit: PlaceEdit) : AddressPurpose
}

/** Откуда взялся адрес на подтверждении. */
enum class AddressSource { TEXT, PHOTO_DEVICE, PHOTO_AI, NAME_AI }

/**
 * Найденный адрес ждёт подтверждения: [aiText] — что назвала модель (у адресов от ИИ), [found] — что нашёл
 * геокодер (координаты — только отсюда). Перед «Адрес верный» предлагаем проверить точку на карте.
 */
data class AddressConfirm(
    val source: AddressSource,
    val found: FoundAddress,
    val purpose: AddressPurpose,
    val aiText: String? = null,
    val tag: ModelTag? = null,
)

data class SearchState(
    val query: String = "",
    /** Результаты геокодера по тексту «Куда?» — выбрать один. */
    val results: List<FoundAddress> = emptyList(),
    /** Предложить поиск по названию через ИИ (геокодер ничего не нашёл). */
    val offerAiName: Boolean = false,
    /** Последний снимок адреса — для «Распознать через ИИ». */
    val addressPhoto: ByteArray? = null,
    val confirm: AddressConfirm? = null,
)

/**
 * Поле «Куда?» (и адрес на экране моего места): мои места → геокодер рядом со мной → по кнопке модель по названию;
 * фото адреса — распознаём на телефоне, «через ИИ» — только по нажатию. Координаты адреса от модели — только
 * от геокодера, точку проверяет пользователь ([AddressConfirm]).
 */
class AddressSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val g = app as GlassesApp
    private val session = g.navSession
    private val geocoder = session.geocoder
    private val ocr = AddressOcr()
    private val ai = AddressAi(g.llm)

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // «Веди в …» голосом не нашёл — запрос в поле, под ним «Найти через ИИ».
            session.prefill.filterNotNull().collect { p ->
                _state.update { it.copy(query = p.query, offerAiName = p.offerAiName) }
                session.prefillConsumed()
            }
        }
        viewModelScope.launch {
            // Цель открыта — результаты поиска больше не нужны.
            session.target.filterNotNull().collect { _state.update { it.copy(results = emptyList(), offerAiName = false) } }
        }
        viewModelScope.launch {
            // Экран места закрыт — его поиск адреса тоже.
            session.place.map { it == null }.distinctUntilChanged().drop(1).filter { it }
                .collect { _state.update { it.copy(results = emptyList(), offerAiName = false) } }
        }
        viewModelScope.launch { session.cleared.collect { _state.value = SearchState() } }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text, offerAiName = false) }
        session.clearNotice()
    }

    /** «Куда?» голосом: «как добраться до вокзала» → ищем «вокзала». */
    fun listenQuery() = session.launchTask(viewModelScope, NavBusy.LISTENING) {
        val language = LanguageSettings.current.value
        val heard = try {
            g.speechSource.listen(language.speechTag)
        } catch (e: SpeechException) {
            session.notify(if (e.noSpeech) null else e.message)
            return@launchTask
        }
        val text = NavQuery.stripPrefix(heard.text, L.array(R.array.nav_query_prefixes))
        _state.update { it.copy(query = text) }
        searchNow(text)
    }

    fun search() = session.launchTask(viewModelScope, NavBusy.SEARCHING) { searchNow(_state.value.query) }

    /** Мои места → геокодер рядом со мной → (по кнопке) модель по названию. */
    private suspend fun searchNow(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val aliases = session.placeAliases()
        NavQuery.matchPlace(text, g.places.places.value, aliases)?.let { place ->
            if (session.purpose == AddressPurpose.Destination) session.openPlaceTarget(place) else session.clearNotice()
            return
        }
        NavQuery.kindOf(text, aliases)?.let { kind ->
            session.notify(L.s(R.string.nav_place_not_saved, L.s(kindTitle(kind))))
            return
        }
        session.setBusy(NavBusy.SEARCHING)
        _state.update { it.copy(results = emptyList(), offerAiName = false) }
        session.clearNotice()
        when (val result = geocoder.search(text, session.here.value?.point(), session.appLocale())) {
            // Геокодер даёт лишь догадку: под результатами — «Не то? Найти через ИИ».
            is GeocodeResult.Found -> _state.update { it.copy(results = result.addresses, offerAiName = true) }
            GeocodeResult.NotFound -> {
                session.notify(L.s(R.string.nav_not_found))
                _state.update { it.copy(offerAiName = true) }
            }
            GeocodeResult.Unavailable -> session.notify(session.geocoderUnavailableText())
        }
    }

    /** Результат поиска выбран: цель — сразу (у неё есть «Проверить на карте»), адрес места — через подтверждение. */
    fun pickResult(found: FoundAddress) {
        when (val purpose = session.purpose) {
            AddressPurpose.Destination -> session.openTarget(Target(_state.value.query.ifBlank { found.line }, found.point, found.line))
            is AddressPurpose.ForPlace -> _state.update { it.copy(confirm = AddressConfirm(AddressSource.TEXT, found, purpose)) }
        }
        _state.update { it.copy(results = emptyList()) }
    }

    /** «Найти через ИИ»: в модель — только название и город со страной, без координат. */
    fun findByNameWithAi() = session.launchTask(viewModelScope, NavBusy.ASKING_AI) {
        val name = _state.value.query.trim().ifEmpty { return@launchTask }
        val near = session.here.value?.let { fix ->
            geocoder.reverse(fix.point(), Locale.ENGLISH)?.let { listOfNotNull(it.locality, it.country).joinToString(", ") }
        }
        val address = try {
            ai.byName(name, near, LanguageSettings.current.value)
        } catch (e: LlmException) {
            session.notify(e.message)
            return@launchTask
        } ?: run {
            session.notify(L.s(R.string.nav_ai_not_sure))
            return@launchTask
        }
        confirmAiAddress(address, AddressSource.NAME_AI)
    }

    /** Фото адреса (визитка, бронь, табличка): снять или выбрать из галереи. Распознаём на телефоне. */
    fun addressFromPhoto(fromGallery: Boolean) = session.launchTask(viewModelScope, NavBusy.CAPTURING) {
        val jpeg = if (fromGallery) {
            session.photos.pickFromGallery()
        } else {
            when (val shot = session.photos.shoot(R.string.nav_viewfinder_address)) {
                is Shot.Taken -> shot.jpeg
                is Shot.Failed -> null.also { session.notify(shot.notice) }
                Shot.None, Shot.Cancelled -> null
            }
        } ?: return@launchTask
        session.setBusy(NavBusy.RECOGNIZING)
        _state.update { it.copy(addressPhoto = jpeg, results = emptyList(), offerAiName = false) }
        session.clearNotice()
        val text = ocr.read(jpeg)
        if (text == null) {
            session.notify(L.s(R.string.nav_photo_not_read))
            return@launchTask
        }
        _state.update { it.copy(query = text) }
        session.setBusy(NavBusy.SEARCHING)
        when (val result = geocoder.search(text, session.here.value?.point(), session.appLocale())) {
            is GeocodeResult.Found -> {
                val found = result.addresses.first()
                _state.update { it.copy(confirm = AddressConfirm(AddressSource.PHOTO_DEVICE, found, session.purpose, aiText = text)) }
            }
            GeocodeResult.NotFound -> session.notify(L.s(R.string.nav_photo_not_found))
            GeocodeResult.Unavailable -> session.notify(session.geocoderUnavailableText())
        }
    }

    /** «Распознать через ИИ» — только по нажатию: снимок уходит модели ассистента (по общим правилам). */
    fun recognizePhotoWithAi() = session.launchTask(viewModelScope, NavBusy.ASKING_AI) {
        val jpeg = _state.value.addressPhoto ?: return@launchTask
        val address = try {
            ai.fromPhoto(jpeg)
        } catch (e: LlmException) {
            session.notify(e.message)
            return@launchTask
        } ?: run {
            session.notify(L.s(R.string.nav_ai_no_address))
            return@launchTask
        }
        confirmAiAddress(address, AddressSource.PHOTO_AI)
    }

    /** Координаты адреса от модели — только от геокодера; не нашёл — показываем, что назвала модель. */
    private suspend fun confirmAiAddress(address: AiAddress, source: AddressSource) {
        session.setBusy(NavBusy.SEARCHING)
        when (val result = geocoder.search(address.text, session.here.value?.point(), session.appLocale())) {
            is GeocodeResult.Found -> _state.update {
                it.copy(confirm = AddressConfirm(source, result.addresses.first(), session.purpose, address.text, address.tag), query = address.text)
            }
            else -> {
                _state.update { it.copy(query = address.text) }
                session.notify(L.s(R.string.nav_ai_address_not_found, address.text))
            }
        }
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /** «Адрес верный»: цель — открыть карточку, адрес места — сохранить место. */
    fun acceptConfirm() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null, addressPhoto = null) }
        when (val purpose = confirm.purpose) {
            AddressPurpose.Destination -> session.openTarget(Target(confirm.found.line, confirm.found.point, confirm.found.line))
            is AddressPurpose.ForPlace -> viewModelScope.launch {
                val saved = session.saver.saveAddress(purpose.edit, confirm.found)
                session.updatePlace(purpose.edit.copy(placeId = saved.id))
                _state.update { it.copy(query = "") }
            }
        }
    }

    fun checkOnMap(point: GeoPoint, label: String) = session.checkOnMap(point, label)

    fun cancel() = session.cancel()
}
