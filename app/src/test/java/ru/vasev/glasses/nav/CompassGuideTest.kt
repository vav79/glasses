package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassGuideTest {

    /** Точка в [northM] метрах к северу от начала. */
    private fun at(northM: Double) = GeoPoint(northM / 111_320.0, 0.0)

    private fun sample(t: Long, distance: Double, side: Side? = Side.AHEAD, walkedNorth: Double = 0.0, accuracy: Float? = 5f) =
        CompassSample(t * 1000, at(walkedNorth), distance, accuracy, side, Cardinal.N)

    @Test
    fun `сектор с запасом против дребезга`() {
        assertEquals(Side.AHEAD, CompassMath.side(10.0, null))
        assertEquals(Side.AHEAD_LEFT, CompassMath.side(-30.0, null))
        // 25° — уже «впереди справа», но прежний «впереди» держится до 32,5°.
        assertEquals(Side.AHEAD, CompassMath.side(25.0, Side.AHEAD))
        assertEquals(Side.AHEAD_RIGHT, CompassMath.side(35.0, Side.AHEAD))
        assertEquals(Side.BEHIND, CompassMath.side(-178.0, null))
        assertEquals(-20.0, CompassMath.relative(350.0, 10.0), 1e-9)
        assertEquals(Cardinal.NW, CompassMath.cardinal(320.0))
        assertEquals(Cardinal.N, CompassMath.cardinal(359.0))
    }

    @Test
    fun `первая подсказка — по прямой с расстоянием и временем`() {
        val cues = CompassGuide().update(sample(0, 338.0, Side.AHEAD_LEFT))
        assertEquals(listOf(Cue.Start(Side.AHEAD_LEFT, Cardinal.N, 350, 6)), cues)
    }

    @Test
    fun `смена стороны — после трёх секунд устойчивости и не чаще раза в восемь секунд`() {
        val g = CompassGuide()
        g.update(sample(0, 400.0, Side.AHEAD))
        assertTrue(g.update(sample(10, 400.0, Side.RIGHT)).isEmpty())
        assertTrue(g.update(sample(12, 400.0, Side.RIGHT)).isEmpty())
        assertEquals(listOf(Cue.Direction(Side.RIGHT, 400)), g.update(sample(13, 400.0, Side.RIGHT)))
        // Вернулся на секунду — не говорим.
        assertTrue(g.update(sample(14, 400.0, Side.AHEAD)).isEmpty())
        assertTrue(g.update(sample(15, 400.0, Side.RIGHT)).isEmpty())
    }

    @Test
    fun `отметки расстояния — по одному разу, дрожание GPS не повторяет`() {
        val g = CompassGuide()
        g.update(sample(0, 620.0))
        assertEquals(listOf(Cue.Distance(500, Side.AHEAD, Cardinal.N)), g.update(sample(60, 495.0, walkedNorth = 125.0)))
        assertTrue(g.update(sample(61, 505.0, walkedNorth = 125.0)).isEmpty())
        assertTrue(g.update(sample(62, 498.0, walkedNorth = 125.0)).isEmpty())
        // Говорим фактическое расстояние, а не саму отметку.
        assertEquals(listOf(Cue.Distance(290, Side.AHEAD, Cardinal.N)), g.update(sample(150, 290.0, walkedNorth = 330.0)))
    }

    @Test
    fun `на месте — в радиусе 20 метров, при плохом GPS шире, и больше ни слова`() {
        val g = CompassGuide()
        g.update(sample(0, 120.0))
        assertTrue(g.update(sample(30, 25.0, walkedNorth = 95.0)).none { it == Cue.Arrived })
        assertEquals(listOf(Cue.Arrived), g.update(sample(40, 18.0, walkedNorth = 102.0)))
        assertTrue(g.update(sample(41, 5.0, walkedNorth = 115.0)).isEmpty())
        assertEquals(35.0, CompassMath.arriveRadius(60f), 1e-9)
        assertEquals(30.0, CompassMath.arriveRadius(25f), 1e-9)
    }

    @Test
    fun `иду две минуты, а ближе не становится — предложить навигатор`() {
        val g = CompassGuide()
        g.update(sample(0, 800.0, Side.AHEAD))
        // Вдоль реки: прошёл 150 м, расстояние почти то же.
        g.update(sample(60, 805.0, Side.LEFT, walkedNorth = 75.0))
        val cues = g.update(sample(121, 798.0, Side.LEFT, walkedNorth = 150.0))
        assertTrue(Cue.Stuck in cues)
        // Стоял на месте — это не «застрял».
        val idle = CompassGuide()
        idle.update(sample(0, 800.0))
        assertTrue(Cue.Stuck !in idle.update(sample(121, 800.0)))
    }

    @Test
    fun `полчаса без движения — автостоп`() {
        val g = CompassGuide()
        g.update(sample(0, 800.0))
        assertTrue(Cue.AutoStop !in g.update(sample(29 * 60L, 800.0, walkedNorth = 10.0)))
        assertEquals(listOf(Cue.AutoStop), g.update(sample(30 * 60L, 800.0, walkedNorth = 12.0)))
        assertTrue(g.isFinished)
    }
}
