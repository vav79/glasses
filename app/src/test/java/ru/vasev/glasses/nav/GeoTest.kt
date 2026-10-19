package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GeoTest {

    private val redSquare = GeoPoint(55.753930, 37.620795)
    private val bolshoi = GeoPoint(55.760186, 37.618711)

    @Test
    fun `расстояние по прямой`() {
        // Красная площадь → Большой театр ≈ 710 м.
        assertEquals(710.0, Geo.distanceM(redSquare, bolshoi), 15.0)
        assertEquals(0.0, Geo.distanceM(redSquare, redSquare), 0.001)
    }

    @Test
    fun `направление относительно севера`() {
        assertEquals(0.0, Geo.bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 0.01)
        assertEquals(90.0, Geo.bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 0.01)
        assertEquals(180.0, Geo.bearingDeg(GeoPoint(1.0, 0.0), GeoPoint(0.0, 0.0)), 0.01)
        assertEquals(270.0, Geo.bearingDeg(GeoPoint(0.0, 1.0), GeoPoint(0.0, 0.0)), 0.01)
    }

    @Test
    fun `время пешком с поправкой на улицы`() {
        // 800 м × 1,3 / 80 м/мин = 13 мин.
        assertEquals(13, Geo.walkMinutes(800.0))
        assertEquals(1, Geo.walkMinutes(5.0))
    }

    @Test
    fun `компас до полутора километров, транспорт первым после 20 минут`() {
        assertTrue(Geo.compassSuitable(1_500.0))
        assertFalse(Geo.compassSuitable(1_501.0))
        // 20 минут — это ≈ 1,23 км по прямой.
        assertFalse(Geo.transitFirst(1_230.0))
        assertTrue(Geo.transitFirst(1_250.0))
    }

    @Test
    fun `подпись расстояния`() {
        val ru = Locale.forLanguageTag("ru")
        assertEquals(DistanceText("340", km = false), Geo.distanceText(338.0, ru))
        assertEquals(DistanceText("10", km = false), Geo.distanceText(2.0, ru))
        assertEquals(DistanceText("6,8", km = true), Geo.distanceText(6_790.0, ru))
        assertEquals(DistanceText("6.8", km = true), Geo.distanceText(6_790.0, Locale.ENGLISH))
        assertEquals(DistanceText("7,080", km = true), Geo.distanceText(7_080_000.0, Locale.ENGLISH))
    }
}
