package ru.vasev.glasses.nav

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Точка на карте. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Расстояние и время пешком — без маршрутов и без сети: по прямой, время с поправкой на петли улиц.
 * Чистая логика, покрыта тестами.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Улицы длиннее прямой: в среднем на треть. */
    const val DETOUR = 1.3

    /** Средний шаг: 4,8 км/ч. */
    const val WALK_M_PER_MIN = 80.0

    /** До этого расстояния по прямой цель ведёт «Компас», дальше — навигатор. */
    const val COMPASS_MAX_M = 1_500.0

    /** Пешком дольше — первыми предлагаются транспорт и такси. */
    const val WALK_FIRST_MAX_MIN = 20

    /** Расстояние по прямой (по дуге большого круга), метры. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Направление от [from] на [to] относительно севера, 0..360°. */
    fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Минут пешком (не меньше одной). */
    fun walkMinutes(distanceM: Double): Int = maxOf(1, ceil(distanceM * DETOUR / WALK_M_PER_MIN).toInt())

    fun compassSuitable(distanceM: Double): Boolean = distanceM <= COMPASS_MAX_M

    fun transitFirst(distanceM: Double): Boolean = walkMinutes(distanceM) > WALK_FIRST_MAX_MIN

    /**
     * Расстояние для экрана: до километра — метры с шагом 10 м, дальше — километры с одним знаком
     * (разделитель — по [locale]: «6,8» / «6.8»), от 100 км — целые километры с разрядами.
     */
    fun distanceText(distanceM: Double, locale: Locale): DistanceText = when {
        distanceM < 1_000 -> DistanceText(maxOf(10, (distanceM / 10).roundToInt() * 10).toString(), km = false)
        distanceM < 100_000 -> DistanceText(String.format(locale, "%.1f", distanceM / 1_000), km = true)
        else -> DistanceText(String.format(locale, "%,d", (distanceM / 1_000).roundToInt()), km = true)
    }
}

/** Число и единица расстояния; подпись («м» / «км») — из ресурсов. */
data class DistanceText(val value: String, val km: Boolean)
