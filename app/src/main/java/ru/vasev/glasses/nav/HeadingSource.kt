package ru.vasev.glasses.nav

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Откуда известно, куда я смотрю: компас телефона в руке или направление движения по GPS. */
enum class HeadingKind { PHONE_COMPASS, WALKING_DIRECTION }

data class Heading(val degrees: Double, val kind: HeadingKind)

/**
 * Направление взгляда без очков. Экран включён — телефон в руке: компас телефона (вектор поворота, истинный север
 * с поправкой на магнитное склонение; телефон лёжа — по верху экрана, стоя — по задней камере). Экран выключен —
 * телефон в кармане, компас там врёт: берём направление движения по GPS, пока иду. Стою с телефоном в кармане —
 * направления нет, подсказки — сторонами света. Позже здесь встанут датчики очков.
 */
class HeadingSource(context: Context) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val rotation = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val matrix = FloatArray(9)
    @Volatile private var sin = 0.0
    @Volatile private var cos = 0.0
    @Volatile private var sensorAt = 0L
    @Volatile private var declination = 0.0
    @Volatile private var course: Double? = null
    @Volatile private var courseAt = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            // Лёжа (ось Z экрана вертикальна) — куда смотрит верх экрана, стоя — куда смотрит задняя камера.
            val (east, north) = if (abs(matrix[8]) > FLAT_Z) matrix[1] to matrix[4] else -matrix[2] to -matrix[5]
            val deg = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
            // Сглаживание по синусу и косинусу: среднее 359° и 1° — 0°, а не 180°.
            val rad = Math.toRadians(deg)
            if (sensorAt == 0L) {
                sin = sin(rad)
                cos = cos(rad)
            } else {
                sin += SMOOTH * (sin(rad) - sin)
                cos += SMOOTH * (cos(rad) - cos)
            }
            sensorAt = SystemClock.elapsedRealtime()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val hasCompass: Boolean get() = rotation != null

    fun start() {
        rotation?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors?.unregisterListener(listener)
        sensorAt = 0L
        course = null
    }

    /** Новая точка GPS: склонение для истинного севера и направление движения, если иду. */
    fun onLocation(location: Location) {
        declination = GeomagneticField(
            location.latitude.toFloat(), location.longitude.toFloat(), location.altitude.toFloat(), System.currentTimeMillis(),
        ).declination.toDouble()
        if (location.hasBearing() && location.hasSpeed() && location.speed >= WALKING_MPS) {
            course = location.bearing.toDouble()
            courseAt = SystemClock.elapsedRealtime()
        }
    }

    fun current(): Heading? {
        val now = SystemClock.elapsedRealtime()
        if (power?.isInteractive == true && sensorAt != 0L && now - sensorAt < FRESH_MS) {
            val magnetic = Math.toDegrees(atan2(sin, cos))
            return Heading(((magnetic + declination) % 360 + 360) % 360, HeadingKind.PHONE_COMPASS)
        }
        val c = course
        if (c != null && now - courseAt < COURSE_FRESH_MS) return Heading(c, HeadingKind.WALKING_DIRECTION)
        return null
    }

    private companion object {
        const val FLAT_Z = 0.7f
        const val SMOOTH = 0.15
        const val FRESH_MS = 2_000L
        const val COURSE_FRESH_MS = 6_000L
        const val WALKING_MPS = 0.8f
    }
}
