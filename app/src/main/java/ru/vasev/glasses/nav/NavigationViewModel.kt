package ru.vasev.glasses.nav

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.places.Destination
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import ru.vasev.glasses.profile.UserRole
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.VoiceStatus
import ru.vasev.glasses.speech.SpeechException
import java.time.Instant
import java.util.Locale

/** Куда идти: подпись, точка, адрес; [placeId] — одно из моих мест. */
data class Target(val label: String, val point: GeoPoint, val address: String?, val placeId: String? = null)

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

/** Экран моего места: [placeId] — существующее, иначе новое вида [kind]. */
data class PlaceEdit(val kind: PlaceKind, val placeId: String? = null, val name: String = "")

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

/** Советы по транспорту в карточке цели; [sent] — что ушло в модель (видно разработчику). */
data class TransitState(
    val loading: Boolean = false,
    val advice: TransitAdvice? = null,
    val error: String? = null,
    val sent: String? = null,
)

/** Что сейчас делается — для подписи и блокировки кнопок. */
enum class NavBusy { LISTENING, SEARCHING, CAPTURING, RECOGNIZING, ASKING_AI, LOCATING }

data class NavState(
    val here: DeviceLocation.Fix? = null,
    val locationGranted: Boolean = false,
    val query: String = "",
    val busy: NavBusy? = null,
    /** Результаты геокодера по тексту «Куда?» — выбрать один. */
    val results: List<FoundAddress> = emptyList(),
    /** Подсказка под полем: «не нашёл», «нет интернета», «отель ещё не сохранён»… */
    val notice: String? = null,
    /** Предложить поиск по названию через ИИ (геокодер ничего не нашёл). */
    val offerAiName: Boolean = false,
    /** Последний снимок адреса — для «Распознать через ИИ». */
    val addressPhoto: ByteArray? = null,
    /** Куда пойдёт адрес со снимка или из поиска, пока открыт экран места. */
    val purpose: AddressPurpose = AddressPurpose.Destination,
    val confirm: AddressConfirm? = null,
    val target: Target? = null,
    val place: PlaceEdit? = null,
    /** Без сети Google пешком не поведёт: окно с Organic Maps (если установлен). */
    val offlineWalk: Target? = null,
    /** Ссылка, которую экран должен открыть (одноразово). */
    val open: MapsLink? = null,
    /** Приложение, которое экран должен запустить (одноразово). */
    val launch: String? = null,
    val taxi: TaxiCard? = null,
    val transit: TransitState? = null,
)

/**
 * Вкладка «Навигация»: мои места, цель («Куда?» голосом или текстом, фото адреса), карточка цели и переход
 * в навигатор. Координаты никогда не уходят в модель; адрес от модели проверяет геокодер, точку — пользователь.
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    private val g = app as GlassesApp
    private val store = g.places
    private val location = g.location
    private val llm = g.llm
    private val geocoder = AddressGeocoder(app, llm::isOnline)
    private val ocr = AddressOcr()
    private val ai = AddressAi(llm)

    val places: StateFlow<List<Place>> = store.places
    val recent: StateFlow<List<Destination>> = store.recent
    val navigatorChoice = g.navigator.choice

    /** Служебное (точность GPS и т. п.) — всегда у разработчика, остальным — только при проблеме. */
    val diagnostics: StateFlow<Boolean> = g.profile.role.map { it == UserRole.DEVELOPER }
        .stateIn(viewModelScope, SharingStarted.Eagerly, g.profile.role.value == UserRole.DEVELOPER)

    /** Есть ключ для модели ассистента — кнопки «через ИИ» активны. */
    val aiReady: StateFlow<Boolean> = llm.ready(Role.ASSISTANT).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(NavState(locationGranted = location.granted))
    val state: StateFlow<NavState> = _state.asStateFlow()

    var locationPermissionRequester: (suspend () -> Boolean)? = null
    var cameraPermissionRequester: (suspend () -> Boolean)? = null
    var glassesPermissionRequester: (suspend () -> Boolean)? = null
    var galleryPicker: (suspend () -> Uri?)? = null
    var cameraOwner: LifecycleOwner? = null

    private var job: Job? = null

    /** Метку модели показываем, как в чате: не основная модель, запасная, «для фото», «без поиска» или роль «Разработчик». */
    fun showTag(tag: ModelTag): Boolean =
        diagnostics.value || tag.viaFallback || tag.autoVision || tag.noWebSearch || tag.title != ModelCatalog.default(Role.ASSISTANT).title

    // --- Где я ---

    /** Обновить координаты (экран виден — раз в несколько секунд). Без разрешения — тихо ничего. */
    fun refreshLocation() {
        _state.update { it.copy(locationGranted = location.granted) }
        // Только debug: компас за столом — подменённое место и в карточках.
        g.compass.fakeLocation?.let { p ->
            _state.update { it.copy(here = DeviceLocation.Fix(p.latitude, p.longitude, FAKE_ACCURACY_M)) }
            return
        }
        if (!location.granted) return
        viewModelScope.launch {
            location.current(LOCATION_TIMEOUT_MS)?.let { fix -> _state.update { it.copy(here = fix) } }
        }
    }

    fun requestLocation() {
        viewModelScope.launch {
            locationPermissionRequester?.invoke()
            refreshLocation()
        }
    }

    // --- Цель ---

    fun setQuery(text: String) = _state.update { it.copy(query = text, notice = null, offerAiName = false) }

    /** «Куда?» голосом: «как добраться до вокзала» → ищем «вокзала». */
    fun listenQuery() = launchTask(NavBusy.LISTENING) {
        val language = LanguageSettings.current.value
        val heard = try {
            g.speechSource.listen(language.speechTag)
        } catch (e: SpeechException) {
            _state.update { it.copy(notice = if (e.noSpeech) null else e.message) }
            return@launchTask
        }
        val text = NavQuery.stripPrefix(heard.text, L.array(R.array.nav_query_prefixes))
        _state.update { it.copy(query = text) }
        searchNow(text)
    }

    fun search() = launchTask(NavBusy.SEARCHING) { searchNow(_state.value.query) }

    /** Мои места → геокодер рядом со мной → (по кнопке) модель по названию. */
    private suspend fun searchNow(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val aliases = placeAliases()
        NavQuery.matchPlace(text, places.value, aliases)?.let { place ->
            if (_state.value.purpose == AddressPurpose.Destination) openPlaceTarget(place) else _state.update { it.copy(notice = null) }
            return
        }
        NavQuery.kindOf(text, aliases)?.let { kind ->
            _state.update { it.copy(notice = L.s(R.string.nav_place_not_saved, L.s(kindTitle(kind)))) }
            return
        }
        _state.update { it.copy(busy = NavBusy.SEARCHING, results = emptyList(), notice = null, offerAiName = false) }
        when (val result = geocoder.search(text, _state.value.here?.point(), appLocale())) {
            // Геокодер даёт лишь догадку: под результатами — «Не то? Найти через ИИ».
            is GeocodeResult.Found -> _state.update { it.copy(results = result.addresses, offerAiName = true) }
            GeocodeResult.NotFound -> _state.update { it.copy(notice = L.s(R.string.nav_not_found), offerAiName = true) }
            GeocodeResult.Unavailable -> _state.update {
                it.copy(notice = L.s(if (llm.isOnline()) R.string.nav_geocoder_unavailable else R.string.nav_offline_search))
            }
        }
    }

    /** Результат поиска выбран: цель — сразу (у неё есть «Проверить на карте»), адрес места — через подтверждение. */
    fun pickResult(found: FoundAddress) {
        when (val purpose = _state.value.purpose) {
            AddressPurpose.Destination -> openTarget(Target(_state.value.query.ifBlank { found.line }, found.point, found.line))
            is AddressPurpose.ForPlace -> _state.update { it.copy(confirm = AddressConfirm(AddressSource.TEXT, found, purpose)) }
        }
        _state.update { it.copy(results = emptyList()) }
    }

    /** «Найти через ИИ»: в модель — только название и город со страной, без координат. */
    fun findByNameWithAi() = launchTask(NavBusy.ASKING_AI) {
        val name = _state.value.query.trim().ifEmpty { return@launchTask }
        val near = _state.value.here?.let { fix ->
            geocoder.reverse(fix.point(), Locale.ENGLISH)?.let { listOfNotNull(it.locality, it.country).joinToString(", ") }
        }
        val address = try {
            ai.byName(name, near, LanguageSettings.current.value)
        } catch (e: LlmException) {
            _state.update { it.copy(notice = e.message) }
            return@launchTask
        } ?: run {
            _state.update { it.copy(notice = L.s(R.string.nav_ai_not_sure)) }
            return@launchTask
        }
        confirmAiAddress(address, AddressSource.NAME_AI)
    }

    /** Фото адреса (визитка, бронь, табличка): снять или выбрать из галереи. Распознаём на телефоне. */
    fun addressFromPhoto(fromGallery: Boolean) = launchTask(NavBusy.CAPTURING) {
        val jpeg = (if (fromGallery) pickFromGallery() else takePhoto()) ?: return@launchTask
        _state.update { it.copy(addressPhoto = jpeg, busy = NavBusy.RECOGNIZING, results = emptyList(), notice = null, offerAiName = false) }
        val text = ocr.read(jpeg)
        if (text == null) {
            _state.update { it.copy(notice = L.s(R.string.nav_photo_not_read)) }
            return@launchTask
        }
        _state.update { it.copy(query = text, busy = NavBusy.SEARCHING) }
        when (val result = geocoder.search(text, _state.value.here?.point(), appLocale())) {
            is GeocodeResult.Found -> {
                val found = result.addresses.first()
                _state.update { it.copy(confirm = AddressConfirm(AddressSource.PHOTO_DEVICE, found, it.purpose, aiText = text)) }
            }
            GeocodeResult.NotFound -> _state.update { it.copy(notice = L.s(R.string.nav_photo_not_found)) }
            GeocodeResult.Unavailable -> _state.update {
                it.copy(notice = L.s(if (llm.isOnline()) R.string.nav_geocoder_unavailable else R.string.nav_offline_search))
            }
        }
    }

    /** «Распознать через ИИ» — только по нажатию: снимок уходит модели ассистента (по общим правилам). */
    fun recognizePhotoWithAi() = launchTask(NavBusy.ASKING_AI) {
        val jpeg = _state.value.addressPhoto ?: return@launchTask
        val address = try {
            ai.fromPhoto(jpeg)
        } catch (e: LlmException) {
            _state.update { it.copy(notice = e.message) }
            return@launchTask
        } ?: run {
            _state.update { it.copy(notice = L.s(R.string.nav_ai_no_address)) }
            return@launchTask
        }
        confirmAiAddress(address, AddressSource.PHOTO_AI)
    }

    /** Координаты адреса от модели — только от геокодера; не нашёл — показываем, что назвала модель. */
    private suspend fun confirmAiAddress(address: AiAddress, source: AddressSource) {
        _state.update { it.copy(busy = NavBusy.SEARCHING) }
        when (val result = geocoder.search(address.text, _state.value.here?.point(), appLocale())) {
            is GeocodeResult.Found -> _state.update {
                it.copy(confirm = AddressConfirm(source, result.addresses.first(), it.purpose, address.text, address.tag), query = address.text)
            }
            else -> _state.update { it.copy(query = address.text, notice = L.s(R.string.nav_ai_address_not_found, address.text)) }
        }
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    /** «Адрес верный». */
    fun acceptConfirm() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null, addressPhoto = null) }
        when (val purpose = confirm.purpose) {
            AddressPurpose.Destination -> openTarget(Target(confirm.found.line, confirm.found.point, confirm.found.line))
            is AddressPurpose.ForPlace -> viewModelScope.launch { savePlaceAt(purpose.edit, confirm.found) }
        }
    }

    fun openPlaceTarget(place: Place) = openTarget(Target(placeLabel(place), place.point, place.address, place.id))

    fun openRecent(destination: Destination) =
        openTarget(Target(destination.label, destination.point, destination.address, destination.placeId?.takeIf { store.byId(it) != null }))

    private fun openTarget(target: Target) {
        _state.update { it.copy(target = target, transit = null, results = emptyList(), notice = null, offerAiName = false) }
        viewModelScope.launch { store.addRecent(Destination(target.label, target.point, target.address, target.placeId)) }
        refreshLocation()
    }

    fun closeTarget() = _state.update { it.copy(target = null) }

    // --- Советы по транспорту ---

    private val transitTips = TransitTips(llm)

    /**
     * Советы по транспорту для цели. [auto] — карточка сама просит, когда пешком дольше 20 минут:
     * только если есть сеть и ключ, иначе остаётся кнопка. В модель — «район, город, страна» начала и цели.
     */
    fun loadTransitTips(auto: Boolean = false) {
        val target = _state.value.target ?: return
        if (_state.value.transit?.let { it.loading || it.advice != null } == true) return
        if (auto && (!aiReady.value || !llm.isOnline())) return
        _state.update { it.copy(transit = TransitState(loading = true)) }
        viewModelScope.launch {
            val language = LanguageSettings.current.value
            val to = area(target.point)
            if (to == null) {
                _state.update { it.copy(transit = TransitState(error = L.s(if (llm.isOnline()) R.string.transit_no_area else R.string.nav_offline_search))) }
                return@launch
            }
            val from = _state.value.here?.let { area(it.point()) }
            val sent = "${from?.describe() ?: "?"} → ${to.describe()}"
            val advice = try {
                transitTips.tips(from, to, language)
            } catch (e: LlmException) {
                _state.update { it.copy(transit = TransitState(error = e.message, sent = sent)) }
                return@launch
            }
            _state.update { s ->
                if (s.target != target) s else s.copy(transit = TransitState(advice = advice, error = if (advice == null) L.s(R.string.transit_nothing) else null, sent = sent))
            }
        }
    }

    fun retryTransitTips() {
        _state.update { it.copy(transit = null) }
        loadTransitTips()
    }

    private suspend fun area(point: GeoPoint): Area? =
        geocoder.reverse(point, Locale.ENGLISH)?.let { a -> a.locality?.let { Area(it, a.district, a.country) } }

    // --- Такси ---

    /** «Адрес для таксиста»: для цели из карточки (или моего места). */
    fun openTaxi() {
        val target = _state.value.target ?: return
        val place = target.placeId?.let(store::byId)
        val stored = place?.let { p ->
            p.localAddress?.takeIf { p.localLanguage == g.taxiAddresses.languageFor(p.country) }
                ?.let { LocalAddress(p.localName, it, p.localLanguage!!, p.localBy) }
        }
        val apps = TaxiApp.entries.filter { installed(it.packageName) }
        val online = llm.isOnline()
        _state.update { it.copy(taxi = TaxiCard(target, PlusCode.encode(target.point), apps, stored, loading = stored == null && online, offline = !online)) }
        if (stored != null || !online) return
        viewModelScope.launch {
            val local = if (place != null) g.taxiAddresses.ensure(place) else g.taxiAddresses.forTarget(target)
            val consent = local == null && place?.kind == PlaceKind.HOME
            _state.update { s -> s.copy(taxi = s.taxi?.copy(local = local, loading = false, homeConsent = consent)) }
        }
    }

    /** «Перевести через ИИ» для «Дома» — явная просьба: только тогда адрес дома уходит модели. */
    fun translateHomeWithAi() {
        val card = _state.value.taxi ?: return
        val place = card.target.placeId?.let(store::byId) ?: return
        _state.update { it.copy(taxi = card.copy(loading = true, homeConsent = false)) }
        viewModelScope.launch {
            val local = g.taxiAddresses.ensure(place, force = true, allowHomeModel = true)
            _state.update { s -> s.copy(taxi = s.taxi?.copy(local = local, loading = false)) }
        }
    }

    fun sameAsApp(language: String) = g.taxiAddresses.sameAsApp(language)

    fun closeTaxi() = _state.update { it.copy(taxi = null) }

    /** Произнести адрес на местном языке — в динамик телефона, для водителя. */
    fun speakTaxi() {
        val local = _state.value.taxi?.local ?: return
        viewModelScope.launch {
            val locale = Locale.forLanguageTag(local.language)
            if (g.audioOutput.voiceStatus(locale) != VoiceStatus.READY) {
                _state.update { it.copy(notice = L.s(R.string.taxi_no_voice)) }
                return@launch
            }
            runCatching { g.audioOutput.speak(listOfNotNull(local.name, local.address).joinToString(". "), locale, AudioChannel.PARTNER) }
        }
    }

    /** Приложение такси: с пунктом назначения, если у него есть документированная ссылка, иначе просто открыть. */
    fun openTaxiApp(app: TaxiApp) {
        val target = _state.value.taxi?.target ?: return
        val link = TaxiLinks.destination(app, target.point, target.label)
        Log.d(TAG, "taxi app=${app.name} with_destination=${link != null}")
        if (link != null) _state.update { it.copy(open = MapsLink(link, app.packageName)) } else _state.update { it.copy(launch = app.packageName) }
    }

    fun launched() = _state.update { it.copy(launch = null) }

    // --- Компас ---

    val compass: StateFlow<CompassState?> = g.compass.state

    /** Разрешение на уведомление компаса (Android 13+); отказ не мешает — компас работает, просто без уведомления. */
    var notificationPermissionRequester: (suspend () -> Boolean)? = null

    /** «Компас» к цели: без интернета, голосом. Нужна геопозиция; уведомление «Стоп» — по возможности. */
    fun startCompass() {
        val target = _state.value.target ?: return
        viewModelScope.launch {
            if (!location.granted && locationPermissionRequester?.invoke() != true) {
                _state.update { it.copy(notice = L.s(R.string.nav_location_needed), locationGranted = false) }
                return@launch
            }
            notificationPermissionRequester?.invoke()
            g.compass.start(target)
        }
    }

    fun compassStatus() = g.compass.sayStatus()

    fun compassContinue() = g.compass.dismissStuck()

    fun closeCompass() = g.compass.close()

    /** Из компаса — в навигатор: там обход препятствий; компас выключаем, чтобы не говорили оба. */
    fun compassToNavigator() {
        val target = g.compass.state.value?.target ?: return
        g.compass.close()
        _state.update { it.copy(target = target) }
        walk()
    }

    // --- Навигатор ---

    fun walk() {
        val target = _state.value.target ?: return
        val app = currentMapsApp()
        if (NavigatorLinks.needsOfflineWarning(app, llm.isOnline())) {
            _state.update { it.copy(offlineWalk = target) }
            return
        }
        open(NavigatorLinks.route(app, TravelMode.WALK, target.point, target.label, _state.value.here?.point()))
    }

    fun transit() {
        val target = _state.value.target ?: return
        open(NavigatorLinks.route(currentMapsApp(), TravelMode.TRANSIT, target.point, target.label, _state.value.here?.point()))
    }

    val organicInstalled: Boolean get() = installed(MapsApp.ORGANIC)

    fun walkWithOrganic() {
        val target = _state.value.offlineWalk ?: return
        _state.update { it.copy(offlineWalk = null) }
        open(NavigatorLinks.route(MapsApp.ORGANIC, TravelMode.WALK, target.point, target.label, _state.value.here?.point()))
    }

    fun dismissOfflineWalk() = _state.update { it.copy(offlineWalk = null) }

    /** Точка на карте — в том же приложении, что и маршруты (иначе система спросит, чем открыть). */
    fun checkOnMap(point: GeoPoint, label: String) {
        val app = currentMapsApp()
        open(NavigatorLinks.geo(point, label).copy(packageName = app.packageName))
    }

    fun linkOpened() = _state.update { it.copy(open = null) }

    private fun open(link: MapsLink) {
        Log.d(TAG, "open ${link.uri.substringBefore(':')} package=${link.packageName}")
        _state.update { it.copy(open = link) }
    }

    /** Какое приложение карт откроется сейчас (настройка, страна, что установлено). */
    fun currentMapsApp(): MapsApp {
        val installed = MapsApp.entries.filter { it.packageName != null && installed(it) }.toSet()
        val country = location.networkCountry() ?: Locale.getDefault().country.takeIf { it.length == 2 }
        return NavigatorLinks.pick(navigatorChoice.value, country, installed)
    }

    private fun installed(app: MapsApp): Boolean = app.packageName?.let(::installed) ?: false

    private fun installed(pkg: String): Boolean {
        return try {
            getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // --- Мои места ---

    fun openPlace(place: Place) = _state.update { it.copy(place = PlaceEdit(place.kind, place.id, place.name), purpose = AddressPurpose.ForPlace(PlaceEdit(place.kind, place.id, place.name))) }

    fun newPlace(kind: PlaceKind) = _state.update { it.copy(place = PlaceEdit(kind), purpose = AddressPurpose.ForPlace(PlaceEdit(kind))) }

    /** Цель — в мои места (своё место с её названием). */
    fun saveTargetAsPlace() {
        val target = _state.value.target ?: return
        viewModelScope.launch {
            val saved = store.save(
                Place("", PlaceKind.CUSTOM, target.label, target.point, null, Instant.now(), address = target.address),
                jpeg = null,
            )
            _state.update { it.copy(target = target.copy(placeId = saved.id)) }
            g.taxiAddresses.refreshLater(saved.id)
        }
    }

    fun setPlaceName(name: String) = _state.update { s ->
        val edit = s.place?.copy(name = name) ?: return@update s
        s.copy(place = edit, purpose = AddressPurpose.ForPlace(edit))
    }

    fun closePlace() = _state.update { it.copy(place = null, purpose = AddressPurpose.Destination, results = emptyList(), notice = null, offerAiName = false) }

    /** «Сохранить здесь»: координаты GPS, фото входа (из выбранного источника) и время; прежнее место того же вида заменяется. */
    fun savePlaceHere() = launchTask(NavBusy.LOCATING) {
        val edit = _state.value.place ?: return@launchTask
        if (!location.granted && locationPermissionRequester?.invoke() != true) {
            _state.update { it.copy(notice = L.s(R.string.nav_location_needed), locationGranted = false) }
            return@launchTask
        }
        val fix = location.current() ?: run {
            _state.update { it.copy(notice = L.s(R.string.nav_no_fix)) }
            return@launchTask
        }
        _state.update { it.copy(here = fix, busy = NavBusy.CAPTURING) }
        val photo = takePhoto(quiet = true)
        // Адрес точки — для подписи и советов по транспорту (город); без сети — без адреса.
        val address = geocoder.reverse(fix.point(), appLocale())
        val saved = store.save(
            Place(
                id = "",
                kind = edit.kind,
                name = edit.name.trim(),
                point = fix.point(),
                accuracyM = fix.accuracyM,
                savedAt = Instant.now(),
                address = address?.line,
                country = address?.country,
                locality = address?.locality,
            ),
            jpeg = photo,
            keepPhoto = photo == null && edit.placeId != null,
            replaceId = edit.placeId,
        )
        g.taxiAddresses.refreshLater(saved.id)
        Log.d(TAG, "place saved kind=${saved.kind} photo=${saved.hasPhoto} accuracy=${fix.accuracyM}")
        _state.update { it.copy(place = edit.copy(placeId = saved.id), purpose = AddressPurpose.ForPlace(edit.copy(placeId = saved.id))) }
    }

    /** Новое фото входа для сохранённого места. */
    fun retakePlacePhoto() = launchTask(NavBusy.CAPTURING) {
        val id = _state.value.place?.placeId ?: return@launchTask
        val photo = takePhoto() ?: return@launchTask
        store.setPhoto(id, photo)
    }

    private suspend fun savePlaceAt(edit: PlaceEdit, found: FoundAddress) {
        val old = edit.placeId?.let(store::byId)
        val saved = store.save(
            Place(
                id = "",
                kind = edit.kind,
                name = edit.name.trim().ifEmpty { old?.name.orEmpty() },
                point = found.point,
                accuracyM = null,
                savedAt = Instant.now(),
                address = found.line,
                country = found.country,
                locality = found.locality,
            ),
            jpeg = null,
            keepPhoto = true,
            replaceId = edit.placeId,
        )
        _state.update { it.copy(place = edit.copy(placeId = saved.id), purpose = AddressPurpose.ForPlace(edit.copy(placeId = saved.id)), query = "") }
        g.taxiAddresses.refreshLater(saved.id)
    }

    fun deletePlace() {
        val id = _state.value.place?.placeId ?: return
        viewModelScope.launch {
            store.delete(id)
            closePlace()
        }
    }

    suspend fun placePhoto(place: Place, maxSide: Int = 1024) = store.photo(place, maxSide)

    fun placeLabel(place: Place): String = place.name.ifBlank { L.s(kindTitle(place.kind)) }

    // --- Снимки ---

    /**
     * Снимок из выбранного источника: очки, если подключены, иначе камера телефона (кнопку нажали на телефоне —
     * он в руке). [quiet] — без сообщений (фото входа необязательно): почти чёрный кадр не прикрепляется.
     */
    private suspend fun takePhoto(quiet: Boolean = false): ByteArray? = try {
        val glasses = g.glasses
        val source = g.imageSources.source.value
        val fromGlasses = (source == ImageSource.GLASSES || source == ImageSource.SIMULATOR) && glasses.status.value.ready
        if (fromGlasses) {
            glasses.captureJpeg(glassesPermissionRequester ?: { false })
        } else if (cameraPermissionRequester?.invoke() == true) {
            (cameraOwner?.let { g.phoneCamera.captureWithoutPreview(it) } ?: g.phoneCamera.capture()).takeUnless {
                quiet && ImagePrep.isDark(it)
            }
        } else {
            if (!quiet) _state.update { it.copy(notice = L.s(R.string.camera_permission_denied)) }
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "photo failed: ${e.javaClass.simpleName}")
        if (!quiet) _state.update { it.copy(notice = L.s(R.string.nav_photo_failed)) }
        null
    }

    private suspend fun pickFromGallery(): ByteArray? {
        val uri = galleryPicker?.invoke() ?: return null
        val resolver = getApplication<Application>().contentResolver
        return withContext(Dispatchers.Default) { ImagePrep.fromUri(resolver, uri) }
    }

    // --- Служебное ---

    /** Одно действие за раз; повторное нажатие во время работы — отменяет прежнее. */
    private fun launchTask(busy: NavBusy, block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(busy = busy) }
            try {
                block()
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(busy = null) }
    }

    private fun appLocale(): Locale = Locale.forLanguageTag(LanguageSettings.current.value.tag)

    private fun placeAliases(): Map<PlaceKind, List<String>> = mapOf(
        PlaceKind.HOTEL to L.array(R.array.nav_alias_hotel),
        PlaceKind.HOME to L.array(R.array.nav_alias_home),
        PlaceKind.CAR to L.array(R.array.nav_alias_car),
    )

    private fun DeviceLocation.Fix.point() = GeoPoint(latitude, longitude)

    companion object {
        private const val TAG = "Navigation"
        private const val LOCATION_TIMEOUT_MS = 4_000L
        private const val FAKE_ACCURACY_M = 5f

        fun kindTitle(kind: PlaceKind): Int = when (kind) {
            PlaceKind.HOTEL -> R.string.place_hotel
            PlaceKind.HOME -> R.string.place_home
            PlaceKind.CAR -> R.string.place_car
            PlaceKind.CUSTOM -> R.string.place_custom
        }
    }
}
