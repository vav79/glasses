package ru.vasev.glasses.nav

import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.places.Destination
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import ru.vasev.glasses.profile.UserRole
import java.util.Locale

/** Куда идти: подпись, точка, адрес; [placeId] — одно из моих мест. */
data class Target(val label: String, val point: GeoPoint, val address: String?, val placeId: String? = null)

/** Экран моего места: [placeId] — существующее, иначе новое вида [kind]. */
data class PlaceEdit(val kind: PlaceKind, val placeId: String? = null, val name: String = "")

/** Что сейчас делается — для подписи и блокировки кнопок. */
enum class NavBusy { LISTENING, SEARCHING, CAPTURING, RECOGNIZING, ASKING_AI, LOCATING }

/** «Веди в …» голосом не нашёл: запрос — в поле «Куда?», под ним «Найти через ИИ». */
data class SearchPrefill(val query: String, val offerAiName: Boolean)

/** Что «Навигации» нужно от Activity: разрешения, выбор фото, жизненный цикл для камеры. Подключается на время жизни Activity. */
class NavHooks(
    val owner: LifecycleOwner,
    val requestLocation: suspend () -> Boolean,
    val requestPhoneCamera: suspend () -> Boolean,
    val requestGlassesCamera: suspend () -> Boolean,
    val requestNotifications: suspend () -> Boolean,
    val pickPhoto: suspend () -> Uri?,
)

/**
 * Общее на всё приложение состояние «Навигации»: что открыто (цель, такси, моё место), где я, подсказка под полем
 * «Куда?», текущее действие и одноразовые переходы в другие приложения. Им пользуются экраны вкладки (каждый со своей
 * ViewModel) и голосовые команды с любой вкладки ([NavVoiceCommands]). Координаты никогда не уходят в модель.
 */
class NavSession(private val g: GlassesApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val location get() = g.location

    val geocoder = AddressGeocoder(g, g.llm::isOnline)
    val photos = PhotoCapture(g, this)
    val saver = PlaceSaver(g.places, geocoder, g.taxiAddresses)

    /** Подключает MainActivity в onCreate, отключает в onDestroy. */
    var hooks: NavHooks? = null
        private set

    fun attach(hooks: NavHooks) {
        this.hooks = hooks
    }

    /** Отключить, только если подключена именно эта Activity (новая могла подключиться раньше). */
    fun detach(owner: LifecycleOwner) {
        if (hooks?.owner === owner) hooks = null
    }

    /** Служебное (точность GPS и т. п.) — всегда у разработчика, остальным — только при проблеме. */
    val diagnostics: StateFlow<Boolean> = g.profile.role.map { it == UserRole.DEVELOPER }
        .stateIn(scope, SharingStarted.Eagerly, g.profile.role.value == UserRole.DEVELOPER)

    /** Есть ключ для модели ассистента — кнопки «через ИИ» активны. */
    val aiReady: StateFlow<Boolean> = g.llm.ready(Role.ASSISTANT).stateIn(scope, SharingStarted.Eagerly, false)

    /** Метку модели показываем, как в чате: не основная модель, запасная, «для фото», «без поиска» или роль «Разработчик». */
    fun showTag(tag: ModelTag): Boolean =
        diagnostics.value || tag.viaFallback || tag.autoVision || tag.noWebSearch || tag.title != ModelCatalog.default(Role.ASSISTANT).title

    // --- Где я ---

    private val _here = MutableStateFlow<DeviceLocation.Fix?>(null)
    val here: StateFlow<DeviceLocation.Fix?> = _here.asStateFlow()

    private val _locationGranted = MutableStateFlow(location.granted)
    val locationGranted: StateFlow<Boolean> = _locationGranted.asStateFlow()

    /** Обновить координаты (экран виден — раз в несколько секунд). Без разрешения — тихо ничего. */
    fun refreshLocation() {
        _locationGranted.value = location.granted
        // Только debug: компас за столом — подменённое место и в карточках.
        g.compass.fakeLocation?.let { p ->
            _here.value = DeviceLocation.Fix(p.latitude, p.longitude, FAKE_ACCURACY_M)
            return
        }
        if (!location.granted) return
        scope.launch {
            location.current(LOCATION_TIMEOUT_MS)?.let { _here.value = it }
        }
    }

    fun requestLocation() {
        scope.launch {
            hooks?.requestLocation?.invoke()
            refreshLocation()
        }
    }

    /** Разрешение на геопозицию есть или пользователь его только что дал; отказ — карточка «Разрешить» на экране. */
    suspend fun ensureLocation(): Boolean {
        if (location.granted || hooks?.requestLocation?.invoke() == true) return true
        _locationGranted.value = false
        return false
    }

    /** Свежая точка «где я» (подменённая в debug); без разрешения — последняя известная экрану. */
    suspend fun freshHere(): GeoPoint? {
        g.compass.fakeLocation?.let { return it }
        if (!location.granted) return _here.value?.point()
        return location.current(LOCATION_TIMEOUT_MS)?.also { _here.value = it }?.point()
    }

    /** Точка для сохранения места: GPS (в debug — подменённая) с точностью. */
    suspend fun fixForPlace(useFake: Boolean): DeviceLocation.Fix? {
        if (useFake) g.compass.fakeLocation?.let { return DeviceLocation.Fix(it.latitude, it.longitude, FAKE_ACCURACY_M) }
        return location.current()?.also { _here.value = it }
    }

    // --- Что открыто ---

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    /** Экран «Адрес для таксиста» для этой цели. */
    private val _taxi = MutableStateFlow<Target?>(null)
    val taxi: StateFlow<Target?> = _taxi.asStateFlow()

    private val _place = MutableStateFlow<PlaceEdit?>(null)
    val place: StateFlow<PlaceEdit?> = _place.asStateFlow()

    /** Куда пойдёт адрес из поиска: открыт экран места — в его адрес, иначе — в цель. */
    val purpose: AddressPurpose get() = _place.value?.let { AddressPurpose.ForPlace(it) } ?: AddressPurpose.Destination

    /** Где я был, когда выбрал цель, — для «назад к началу маршрута»; хранится, переживает выгрузку процесса. */
    var routeStart: GeoPoint?
        get() = g.navigator.routeStart
        set(value) { g.navigator.routeStart = value }

    /** Открыть карточку цели: запомнить начало маршрута (кроме самого «назад к началу») и недавнюю цель. */
    fun openTarget(target: Target) {
        if (target.label != L.s(R.string.nav_route_start)) _here.value?.let { routeStart = it.point() }
        _target.value = target
        clearNotice()
        scope.launch { g.places.addRecent(Destination(target.label, target.point, target.address, target.placeId)) }
        refreshLocation()
    }

    fun openPlaceTarget(place: Place) = openTarget(Target(placeLabel(place), place.point, place.address, place.id))

    /** Цель без начала маршрута и недавних: из компаса обратно в карточку, такси по голосу. */
    fun showTarget(target: Target) {
        _target.value = target
    }

    fun closeTarget() {
        _target.value = null
    }

    /** «Адрес для таксиста» для открытой цели. */
    fun openTaxi() {
        _taxi.value = _target.value ?: return
    }

    fun closeTaxi() {
        _taxi.value = null
    }

    fun openPlace(place: Place) {
        _place.value = PlaceEdit(place.kind, place.id, place.name)
    }

    fun newPlace(kind: PlaceKind) {
        _place.value = PlaceEdit(kind)
    }

    fun updatePlace(edit: PlaceEdit) {
        if (_place.value != null) _place.value = edit
    }

    fun closePlace() {
        _place.value = null
        clearNotice()
    }

    // --- Подсказка под полем «Куда?» (и на экране места) ---

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun notify(text: String?) {
        _notice.value = text
    }

    fun clearNotice() = notify(null)

    /** Текст, когда геокодер не ответил: нет сервиса или нет сети. */
    fun geocoderUnavailableText(): String =
        L.s(if (g.llm.isOnline()) R.string.nav_geocoder_unavailable else R.string.nav_offline_search)

    private val _prefill = MutableStateFlow<SearchPrefill?>(null)
    val prefill: StateFlow<SearchPrefill?> = _prefill.asStateFlow()

    fun prefillSearch(prefill: SearchPrefill) {
        _prefill.value = prefill
    }

    fun prefillConsumed() {
        _prefill.value = null
    }

    // --- Одно действие за раз ---

    private val _busy = MutableStateFlow<NavBusy?>(null)
    val busy: StateFlow<NavBusy?> = _busy.asStateFlow()
    private var job: Job? = null

    /** Поиск, снимок, сохранение места: повторное нажатие во время работы отменяет прежнее действие. */
    fun launchTask(taskScope: CoroutineScope, busy: NavBusy, block: suspend () -> Unit) {
        job?.cancel()
        job = taskScope.launch {
            _busy.value = busy
            try {
                block()
            } finally {
                _busy.value = null
            }
        }
    }

    /** Этап внутри действия: «распознаю» → «ищу». */
    fun setBusy(busy: NavBusy) {
        _busy.value = busy
    }

    fun cancel() {
        job?.cancel()
        _busy.value = null
    }

    // --- Переходы в другие приложения (одноразово: экран открывает и сообщает) ---

    private val _open = MutableStateFlow<MapsLink?>(null)
    val open: StateFlow<MapsLink?> = _open.asStateFlow()

    private val _launch = MutableStateFlow<String?>(null)
    val launch: StateFlow<String?> = _launch.asStateFlow()

    fun open(link: MapsLink) {
        Log.d(TAG, "open ${link.uri.substringBefore(':')} package=${link.packageName}")
        _open.value = link
    }

    fun linkOpened() {
        _open.value = null
    }

    fun launch(packageName: String) {
        _launch.value = packageName
    }

    fun launched() {
        _launch.value = null
    }

    /** Точка на карте — в том же приложении, что и маршруты (иначе система спросит, чем открыть). */
    fun checkOnMap(point: GeoPoint, label: String) {
        open(NavigatorLinks.geo(point, label).copy(packageName = currentMapsApp().packageName))
    }

    /** Какое приложение карт откроется сейчас (настройка, страна, что установлено). */
    fun currentMapsApp(): MapsApp {
        val installed = MapsApp.entries.filter { installed(it) }.toSet()
        val country = location.networkCountry() ?: Locale.getDefault().country.takeIf { it.length == 2 }
        return NavigatorLinks.pick(g.navigator.choice.value, country, installed)
    }

    fun installed(app: MapsApp): Boolean = app.packageName?.let(::installed) ?: false

    fun installed(pkg: String): Boolean = try {
        g.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // --- Компас ---

    /** «Компас» к цели: нужна геопозиция; уведомление «Стоп» — по возможности (отказ компасу не мешает). */
    suspend fun startCompass(target: Target): Boolean {
        if (!ensureLocation()) return false
        hooks?.requestNotifications?.invoke()
        g.compass.start(target)
        return true
    }

    /** Приложение закрыли («Назад» с главного экрана): экраны вкладки — с начала, как при новом запуске. Компас работает дальше. */
    fun closeScreens() {
        cancel()
        _target.value = null
        _taxi.value = null
        _place.value = null
        _prefill.value = null
        clearNotice()
    }

    // --- Очистка ---

    private val _cleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** «Очистить места и историю» — экраны сбрасывают своё состояние. */
    val cleared: SharedFlow<Unit> = _cleared.asSharedFlow()

    /** Места с фото и переводами, недавние, начало маршрута, открытые экраны, компас. Координаты «где я» остаются. */
    fun clearAll() {
        closeScreens()
        g.compass.close()
        routeStart = null
        _cleared.tryEmit(Unit)
        scope.launch {
            g.places.clear()
            Log.d(TAG, "places and history cleared")
        }
    }

    fun appLocale(): Locale = Locale.forLanguageTag(LanguageSettings.current.value.tag)

    fun placeAliases(): Map<PlaceKind, List<String>> = mapOf(
        PlaceKind.HOTEL to L.array(R.array.nav_alias_hotel),
        PlaceKind.HOME to L.array(R.array.nav_alias_home),
        PlaceKind.CAR to L.array(R.array.nav_alias_car),
    )

    companion object {
        const val TAG = "Navigation"
        private const val LOCATION_TIMEOUT_MS = 4_000L
        private const val FAKE_ACCURACY_M = 5f
    }
}

/** Подпись моего места: своё название или вид («Отель»). */
fun placeLabel(place: Place): String = place.name.ifBlank { L.s(kindTitle(place.kind)) }

@StringRes
fun kindTitle(kind: PlaceKind): Int = when (kind) {
    PlaceKind.HOTEL -> R.string.place_hotel
    PlaceKind.HOME -> R.string.place_home
    PlaceKind.CAR -> R.string.place_car
    PlaceKind.CUSTOM -> R.string.place_custom
}

fun DeviceLocation.Fix.point() = GeoPoint(latitude, longitude)
