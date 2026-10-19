package ru.vasev.glasses.nav

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.AudioOutput

/** Что показывает экран «Компаса». [relativeDeg] — куда повернуть стрелку (null — взгляд неизвестен, стрелка на север). */
data class CompassState(
    val target: Target,
    val running: Boolean = true,
    val distanceM: Double? = null,
    val accuracyM: Float? = null,
    val bearingDeg: Double? = null,
    val relativeDeg: Double? = null,
    val side: Side? = null,
    val cardinal: Cardinal? = null,
    val heading: HeadingKind? = null,
    val lastSpoken: String? = null,
    val lastSpokenAt: Long? = null,
    /** Ближе не становится — предложить навигатор (баннер до «Продолжить»). */
    val stuck: Boolean = false,
    val arrived: Boolean = false,
    /** Выключился сам: полчаса без движения. */
    val autoStopped: Boolean = false,
)

/**
 * «Компас» — без интернета, по GPS телефона и компасу: голосом в канал «мне» (гарнитура, потом — очки)
 * направление относительно взгляда, расстояние и время; подсказки — по [CompassGuide]. Живёт на всё приложение:
 * работает при смене вкладки и с выключенным экраном (фоновый сервис [CompassService] с типом location).
 */
class CompassController(
    private val context: Context,
    private val output: AudioOutput,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = context.getSystemService(LocationManager::class.java)
    private val heading = HeadingSource(context)
    private val speech = Channel<String>(Channel.CONFLATED)

    private val _state = MutableStateFlow<CompassState?>(null)
    val state: StateFlow<CompassState?> = _state.asStateFlow()

    private var guide = CompassGuide()
    private var lastFix: Location? = null
    private var ticker: Job? = null
    private var speaker: Job? = null
    private var startedAt = 0L

    /** Только debug: подменённое место — проверить компас за столом (`--es fake_location 'lat,lng'`). */
    var fakeLocation: GeoPoint? = null
        set(value) {
            field = value
            if (value != null && BuildConfig.DEBUG) onLocation(fake(value))
        }

    private val listener = LocationListenerCompat { location -> if (fakeLocation == null) onLocation(location) }

    @SuppressLint("MissingPermission")
    fun start(target: Target) {
        stopWork()
        guide = CompassGuide()
        lastFix = null
        startedAt = SystemClock.elapsedRealtime()
        _state.value = CompassState(target)
        ContextCompat.startForegroundService(context, Intent(context, CompassService::class.java))
        heading.start()
        manager?.let { m ->
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in m.getProviders(true) -> LocationManager.FUSED_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
            val request = LocationRequestCompat.Builder(FIX_INTERVAL_MS).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
            runCatching { LocationManagerCompat.requestLocationUpdates(m, provider, request, listener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "location updates failed: ${it.javaClass.simpleName}") }
        }
        fakeLocation?.let { onLocation(fake(it)) }
        speaker = scope.launch {
            for (text in speech) {
                runCatching { output.speak(text, LanguageSettings.current.value.locale, AudioChannel.ME) }
                    .onFailure { Log.w(TAG, "speak failed: ${it.javaClass.simpleName}") }
            }
        }
        // Раз в секунду — стрелка и правила подсказок (сторона меняется и без новой точки GPS — я повернулся).
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
        Log.d(TAG, "compass started compass_sensor=${heading.hasCompass}")
    }

    /** «Сколько осталось?» — сказать сейчас. */
    fun sayStatus() {
        val s = sample() ?: return
        say(phrase(guide.status(s)))
    }

    fun dismissStuck() = _state.update { it?.copy(stuck = false) }

    /** Остановить и закрыть экран компаса. */
    fun close() {
        stopWork()
        _state.value = null
    }

    private fun stopWork() {
        ticker?.cancel()
        speaker?.cancel()
        manager?.let { LocationManagerCompat.removeUpdates(it, listener) }
        heading.stop()
        context.stopService(Intent(context, CompassService::class.java))
        _state.update { it?.copy(running = false) }
    }

    private fun onLocation(location: Location) {
        lastFix = location
        heading.onLocation(location)
        tick()
    }

    private fun tick() {
        val current = _state.value ?: return
        if (!current.running) return
        val s = sample() ?: return
        val bearing = bearingTo(current.target)
        val head = heading.current()
        _state.value = current.copy(
            distanceM = s.distanceM,
            accuracyM = s.accuracyM,
            bearingDeg = bearing,
            relativeDeg = head?.let { CompassMath.relative(bearing!!, it.degrees) },
            side = s.side,
            cardinal = s.cardinal,
            heading = head?.kind,
        )
        CompassService.update(context, notificationText(current.target.label, s))
        // Первая подсказка — с направлением взгляда: датчику нужна секунда-другая, без него — сторонами света.
        if (head == null && heading.hasCompass && SystemClock.elapsedRealtime() - startedAt < HEADING_WARMUP_MS) return
        for (cue in guide.update(s)) {
            say(phrase(cue))
            when (cue) {
                Cue.Arrived -> finish(arrived = true)
                Cue.AutoStop -> finish(arrived = false)
                Cue.Stuck -> _state.update { it?.copy(stuck = true) }
                else -> Unit
            }
        }
    }

    /** Закончили: последняя фраза ещё договаривается, потом всё выключается. */
    private fun finish(arrived: Boolean) {
        _state.update { it?.copy(arrived = arrived, autoStopped = !arrived, stuck = false) }
        ticker?.cancel()
        manager?.let { LocationManagerCompat.removeUpdates(it, listener) }
        heading.stop()
        scope.launch {
            delay(FINISH_SPEECH_MS)
            stopWork()
        }
        Log.d(TAG, "compass finished arrived=$arrived")
    }

    private fun sample(): CompassSample? {
        val fix = lastFix ?: return null
        val target = _state.value?.target ?: return null
        val here = GeoPoint(fix.latitude, fix.longitude)
        val bearing = Geo.bearingDeg(here, target.point)
        val head = heading.current()
        val previous = _state.value?.side
        return CompassSample(
            timeMs = SystemClock.elapsedRealtime(),
            position = here,
            distanceM = Geo.distanceM(here, target.point),
            accuracyM = if (fix.hasAccuracy()) fix.accuracy else null,
            side = head?.let { CompassMath.side(CompassMath.relative(bearing, it.degrees), previous) },
            cardinal = CompassMath.cardinal(bearing),
        )
    }

    private fun bearingTo(target: Target): Double? = lastFix?.let { Geo.bearingDeg(GeoPoint(it.latitude, it.longitude), target.point) }

    private fun say(text: String) {
        Log.d(TAG, "say: $text")
        _state.update { it?.copy(lastSpoken = text, lastSpokenAt = System.currentTimeMillis()) }
        speech.trySend(text)
    }

    private fun phrase(cue: Cue): String {
        val label = _state.value?.target?.label.orEmpty()
        return when (cue) {
            is Cue.Start -> L.s(R.string.compass_start, label, where(cue.side, cue.cardinal), L.q(R.plurals.compass_meters, cue.meters), L.q(R.plurals.compass_about_minutes, cue.minutes))
            is Cue.Direction -> L.s(R.string.compass_direction, label, L.s(sideText(cue.side)), L.q(R.plurals.compass_meters, cue.meters))
            is Cue.Distance -> L.s(R.string.compass_distance, L.q(R.plurals.compass_meters, cue.meters), where(cue.side, cue.cardinal))
            Cue.Arrived -> L.s(R.string.compass_arrived, label)
            Cue.Stuck -> L.s(R.string.compass_stuck)
            Cue.AutoStop -> L.s(R.string.compass_auto_stop)
        }
    }

    private fun where(side: Side?, cardinal: Cardinal) = L.s(side?.let(::sideText) ?: cardinalText(cardinal))

    private fun notificationText(label: String, s: CompassSample): String =
        "$label · ${L.q(R.plurals.compass_meters, CompassMath.spokenMeters(s.distanceM))} · ${where(s.side, s.cardinal)}"

    private fun fake(point: GeoPoint) = Location("fake").apply {
        latitude = point.latitude
        longitude = point.longitude
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    companion object {
        private const val TAG = "Compass"
        private const val FIX_INTERVAL_MS = 1_000L
        private const val TICK_MS = 1_000L
        private const val FINISH_SPEECH_MS = 6_000L
        private const val HEADING_WARMUP_MS = 2_000L

        fun sideText(side: Side): Int = when (side) {
            Side.AHEAD -> R.string.side_ahead
            Side.AHEAD_RIGHT -> R.string.side_ahead_right
            Side.RIGHT -> R.string.side_right
            Side.BEHIND_RIGHT -> R.string.side_behind_right
            Side.BEHIND -> R.string.side_behind
            Side.BEHIND_LEFT -> R.string.side_behind_left
            Side.LEFT -> R.string.side_left
            Side.AHEAD_LEFT -> R.string.side_ahead_left
        }

        fun cardinalText(cardinal: Cardinal): Int = when (cardinal) {
            Cardinal.N -> R.string.cardinal_n
            Cardinal.NE -> R.string.cardinal_ne
            Cardinal.E -> R.string.cardinal_e
            Cardinal.SE -> R.string.cardinal_se
            Cardinal.S -> R.string.cardinal_s
            Cardinal.SW -> R.string.cardinal_sw
            Cardinal.W -> R.string.cardinal_w
            Cardinal.NW -> R.string.cardinal_nw
        }
    }
}
