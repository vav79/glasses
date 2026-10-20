package ru.vasev.glasses.nav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.places.Place
import java.util.Locale

/** Советы по транспорту в карточке цели; [sent] — что ушло в модель (видно разработчику). */
data class TransitState(
    val loading: Boolean = false,
    val advice: TransitAdvice? = null,
    val error: String? = null,
    val sent: String? = null,
)

/**
 * Карточка цели и «Компас»: переход в навигатор (пешком, транспорт; без сети — Organic Maps), советы по транспорту,
 * цель — в мои места. Сама цель — в [NavSession]: её открывают поиск, мои места и голосовые команды.
 */
class TargetViewModel(app: Application) : AndroidViewModel(app) {

    private val g = app as GlassesApp
    private val session = g.navSession
    private val llm = g.llm
    private val transitTips = TransitTips(llm)

    val compass: StateFlow<CompassState?> = g.compass.state

    private val _transit = MutableStateFlow<TransitState?>(null)
    val transit: StateFlow<TransitState?> = _transit.asStateFlow()

    /** Без сети Google пешком не поведёт: окно с Organic Maps (если установлен). */
    private val _offlineWalk = MutableStateFlow<Target?>(null)
    val offlineWalk: StateFlow<Target?> = _offlineWalk.asStateFlow()

    init {
        viewModelScope.launch {
            // Новая цель — советы прежней не показываем.
            var last = session.target.value
            session.target.collect { if (it != last) _transit.value = null; last = it }
        }
        viewModelScope.launch {
            session.cleared.collect {
                transitTips.clear()
                _transit.value = null
                _offlineWalk.value = null
            }
        }
    }

    fun closeTarget() = session.closeTarget()

    // --- Навигатор ---

    fun walk() {
        val target = session.target.value ?: return
        val app = session.currentMapsApp()
        if (NavigatorLinks.needsOfflineWarning(app, llm.isOnline())) {
            _offlineWalk.value = target
            return
        }
        session.open(NavigatorLinks.route(app, TravelMode.WALK, target.point, target.label, session.here.value?.point()))
    }

    fun transit() {
        val target = session.target.value ?: return
        session.open(NavigatorLinks.route(session.currentMapsApp(), TravelMode.TRANSIT, target.point, target.label, session.here.value?.point()))
    }

    val organicInstalled: Boolean get() = session.installed(MapsApp.ORGANIC)

    fun walkWithOrganic() {
        val target = _offlineWalk.value ?: return
        _offlineWalk.value = null
        session.open(NavigatorLinks.route(MapsApp.ORGANIC, TravelMode.WALK, target.point, target.label, session.here.value?.point()))
    }

    fun dismissOfflineWalk() {
        _offlineWalk.value = null
    }

    fun currentMapsApp(): MapsApp = session.currentMapsApp()

    fun checkOnMap(point: GeoPoint, label: String) = session.checkOnMap(point, label)

    fun openTaxi() = session.openTaxi()

    /** Цель — в мои места (своё место с её названием). */
    fun saveTargetAsPlace() {
        val target = session.target.value ?: return
        viewModelScope.launch {
            val saved = session.saver.saveTarget(target)
            session.showTarget(target.copy(placeId = saved.id))
        }
    }

    fun openPlace(place: Place) = session.openPlace(place)

    // --- Советы по транспорту ---

    /**
     * Советы по транспорту для цели. [auto] — карточка сама просит, когда пешком дольше 20 минут:
     * только если есть сеть и ключ, иначе остаётся кнопка. В модель — «район, город, страна» начала и цели.
     */
    fun loadTransitTips(auto: Boolean = false) {
        val target = session.target.value ?: return
        if (_transit.value?.let { it.loading || it.advice != null } == true) return
        if (auto && (!session.aiReady.value || !llm.isOnline())) return
        _transit.value = TransitState(loading = true)
        viewModelScope.launch {
            val language = LanguageSettings.current.value
            val to = area(target.point)
            if (to == null) {
                _transit.value = TransitState(error = L.s(if (llm.isOnline()) R.string.transit_no_area else R.string.nav_offline_search))
                return@launch
            }
            val from = session.here.value?.let { area(it.point()) }
            val sent = "${from?.describe() ?: "?"} → ${to.describe()}"
            val advice = try {
                transitTips.tips(from, to, language)
            } catch (e: LlmException) {
                _transit.value = TransitState(error = e.message, sent = sent)
                return@launch
            }
            if (session.target.value == target) {
                _transit.value = TransitState(advice = advice, error = if (advice == null) L.s(R.string.transit_nothing) else null, sent = sent)
            }
        }
    }

    fun retryTransitTips() {
        _transit.value = null
        loadTransitTips()
    }

    private suspend fun area(point: GeoPoint): Area? =
        session.geocoder.reverse(point, Locale.ENGLISH)?.let { a -> a.locality?.let { Area(it, a.district, a.country) } }

    // --- Компас ---

    /** «Компас» к цели: без интернета, голосом. Нужна геопозиция; уведомление «Стоп» — по возможности. */
    fun startCompass() {
        val target = session.target.value ?: return
        viewModelScope.launch {
            if (!session.startCompass(target)) session.notify(L.s(R.string.nav_location_needed))
        }
    }

    fun compassStatus() = g.compass.sayStatus()

    fun compassContinue() = g.compass.dismissStuck()

    fun closeCompass() = g.compass.close()

    /** Из компаса — в навигатор: там обход препятствий; компас выключаем, чтобы не говорили оба. */
    fun compassToNavigator() {
        val target = g.compass.state.value?.target ?: return
        g.compass.close()
        session.showTarget(target)
        walk()
    }

    suspend fun placePhoto(place: Place, maxSide: Int = 1024) = g.places.photo(place, maxSide)
}
