package ru.vasev.glasses.nav

import kotlin.math.abs
import kotlin.math.roundToInt

/** Где цель относительно взгляда: восемь секторов по 45°. */
enum class Side(val centerDeg: Double) {
    AHEAD(0.0), AHEAD_RIGHT(45.0), RIGHT(90.0), BEHIND_RIGHT(135.0),
    BEHIND(180.0), BEHIND_LEFT(-135.0), LEFT(-90.0), AHEAD_LEFT(-45.0),
}

/** Сторона света — когда направление взгляда неизвестно (телефон в кармане, я стою). */
enum class Cardinal { N, NE, E, SE, S, SW, W, NW }

object CompassMath {

    /** Цель относительно взгляда, −180..180° (минус — слева). */
    fun relative(bearingDeg: Double, headingDeg: Double): Double {
        var d = (bearingDeg - headingDeg) % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    /**
     * Сектор с запасом [hysteresisDeg]: прежний сектор держится, пока угол не уйдёт за его границу дальше запаса —
     * на границе «впереди / впереди слева» подсказка не прыгает туда-обратно.
     */
    fun side(relativeDeg: Double, previous: Side?, hysteresisDeg: Double = HYSTERESIS_DEG): Side {
        if (previous != null && abs(relative(relativeDeg, previous.centerDeg)) <= HALF_SECTOR + hysteresisDeg) return previous
        return Side.entries.minBy { abs(relative(relativeDeg, it.centerDeg)) }
    }

    fun cardinal(bearingDeg: Double): Cardinal = Cardinal.entries[(((bearingDeg % 360 + 360) % 360 + HALF_SECTOR) / 45).toInt() % 8]

    /** Расстояние для речи: до 300 м — с шагом 10 м, дальше — 50 м. */
    fun spokenMeters(distanceM: Double): Int =
        if (distanceM < 300) maxOf(10, (distanceM / 10).roundToInt() * 10) else (distanceM / 50).roundToInt() * 50

    /** «Вы на месте»: 20 м, а при плохом GPS — с запасом на точность, но не дальше 35 м. */
    fun arriveRadius(accuracyM: Float?): Double = when {
        accuracyM == null || accuracyM <= 15f -> ARRIVE_M
        else -> minOf(accuracyM + 5.0, ARRIVE_MAX_M)
    }

    private const val HALF_SECTOR = 22.5
    private const val HYSTERESIS_DEG = 10.0
    const val ARRIVE_M = 20.0
    private const val ARRIVE_MAX_M = 35.0
}

/** Одно измерение: где я, далеко ли цель, где она относительно взгляда ([side] null — взгляд неизвестен). */
data class CompassSample(
    val timeMs: Long,
    val position: GeoPoint,
    val distanceM: Double,
    val accuracyM: Float?,
    val side: Side?,
    val cardinal: Cardinal,
)

/** Что сказать. Текст собирает контроллер из ресурсов на языке приложения. */
sealed interface Cue {
    /** Первая подсказка — «по прямой», чтобы не путали с длиной маршрута. */
    data class Start(val side: Side?, val cardinal: Cardinal, val meters: Int, val minutes: Int) : Cue
    data class Direction(val side: Side, val meters: Int) : Cue
    data class Distance(val meters: Int, val side: Side?, val cardinal: Cardinal) : Cue
    data object Arrived : Cue

    /** Иду, а ближе не становится: впереди река, трасса или забор — навигатор найдёт обход. */
    data object Stuck : Cue

    /** Полчаса без движения — компас выключается сам. */
    data object AutoStop : Cue
}

/**
 * Когда и что подсказывать в «Компасе». Чистая логика (тесты), время — из измерений:
 * - в начале — направление, расстояние «по прямой» и время;
 * - сектор сменился и держится [STABLE_MS] — новое направление (не чаще раза в [MIN_GAP_MS]);
 * - отметки 500/300/200/100/50 м — по минимальному расстоянию, дрожание GPS их не повторяет;
 * - в радиусе [CompassMath.arriveRadius] — «Вы на месте», дальше подсказок нет;
 * - за [STUCK_WINDOW_MS] прошёл больше [STUCK_WALKED_M], а ближе не стало и на [STUCK_GAIN_M] — «предложить навигатор»;
 * - [IDLE_STOP_MS] без перемещения дальше [MOVE_M] — автостоп.
 */
class CompassGuide {

    private var started = false
    private var finished = false
    private var spokenSide: Side? = null
    private var candidate: Side? = null
    private var candidateSince = 0L
    private var lastSpokenAt = 0L
    private var minDistance = Double.MAX_VALUE
    private val milestonesLeft = MILESTONES.toMutableList()

    private var anchor: GeoPoint? = null
    private var lastMoveAt = 0L
    private var windowStart = 0L
    private var windowDistance = 0.0
    private var walkedInWindow = 0.0
    private var stuckAt = Long.MIN_VALUE / 2

    val isFinished: Boolean get() = finished

    fun update(s: CompassSample): List<Cue> {
        if (finished) return emptyList()
        if (!started) {
            started = true
            spokenSide = s.side
            lastSpokenAt = s.timeMs
            minDistance = s.distanceM
            milestonesLeft.removeAll { it >= s.distanceM }
            anchor = s.position
            lastMoveAt = s.timeMs
            windowStart = s.timeMs
            windowDistance = s.distanceM
            if (s.distanceM <= CompassMath.arriveRadius(s.accuracyM)) return finish()
            return listOf(Cue.Start(s.side, s.cardinal, CompassMath.spokenMeters(s.distanceM), Geo.walkMinutes(s.distanceM)))
        }
        if (s.distanceM <= CompassMath.arriveRadius(s.accuracyM)) return finish()

        trackMovement(s)
        if (s.timeMs - lastMoveAt >= IDLE_STOP_MS) {
            finished = true
            return listOf(Cue.AutoStop)
        }

        val cues = mutableListOf<Cue>()
        stuck(s)?.let(cues::add)

        // Отметка расстояния важнее смены сектора: в ней уже есть направление.
        minDistance = minOf(minDistance, s.distanceM)
        val passed = milestonesLeft.filter { it >= minDistance }
        if (passed.isNotEmpty()) {
            milestonesLeft.removeAll(passed)
            cues += Cue.Distance(CompassMath.spokenMeters(s.distanceM), s.side, s.cardinal)
            spokenSide = s.side ?: spokenSide
            candidate = null
            lastSpokenAt = s.timeMs
            return cues
        }

        val side = s.side
        if (side == null || side == spokenSide) {
            candidate = null
        } else {
            if (side != candidate) {
                candidate = side
                candidateSince = s.timeMs
            }
            if (s.timeMs - candidateSince >= STABLE_MS && s.timeMs - lastSpokenAt >= MIN_GAP_MS) {
                cues += Cue.Direction(side, CompassMath.spokenMeters(s.distanceM))
                spokenSide = side
                candidate = null
                lastSpokenAt = s.timeMs
            }
        }
        return cues
    }

    /** «Сколько осталось?» — сказать сейчас, не дожидаясь подсказки. */
    fun status(s: CompassSample): Cue.Start {
        lastSpokenAt = s.timeMs
        spokenSide = s.side ?: spokenSide
        return Cue.Start(s.side, s.cardinal, CompassMath.spokenMeters(s.distanceM), Geo.walkMinutes(s.distanceM))
    }

    private fun finish(): List<Cue> {
        finished = true
        return listOf(Cue.Arrived)
    }

    /** Перемещение считаем от якоря: шаг GPS-дрожания внутри [MOVE_M] — не движение. */
    private fun trackMovement(s: CompassSample) {
        val from = anchor ?: s.position
        val moved = Geo.distanceM(from, s.position)
        if (moved >= MOVE_M) {
            anchor = s.position
            lastMoveAt = s.timeMs
            walkedInWindow += moved
        }
    }

    private fun stuck(s: CompassSample): Cue? {
        if (s.timeMs - windowStart < STUCK_WINDOW_MS) return null
        val gained = windowDistance - s.distanceM
        val walking = walkedInWindow >= STUCK_WALKED_M
        windowStart = s.timeMs
        windowDistance = s.distanceM
        walkedInWindow = 0.0
        if (!walking || gained >= STUCK_GAIN_M || s.timeMs - stuckAt < STUCK_COOLDOWN_MS) return null
        stuckAt = s.timeMs
        return Cue.Stuck
    }

    companion object {
        val MILESTONES = listOf(1_000.0, 500.0, 300.0, 200.0, 100.0, 50.0)
        const val STABLE_MS = 3_000L
        const val MIN_GAP_MS = 8_000L
        const val MOVE_M = 25.0
        const val IDLE_STOP_MS = 30 * 60_000L
        const val STUCK_WINDOW_MS = 2 * 60_000L
        const val STUCK_WALKED_M = 60.0
        const val STUCK_GAIN_M = 20.0
        const val STUCK_COOLDOWN_MS = 5 * 60_000L
    }
}
