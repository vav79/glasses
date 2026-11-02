package ru.vasev.glasses.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcTest {

    /** NAL-блок: начальный код + заголовок из двух байт (тип << 1) + полезная нагрузка. */
    private fun nal(type: Int, payload: Int = 3, long: Boolean = true): ByteArray =
        (if (long) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)) +
            byteArrayOf((type shl 1).toByte(), 1) + ByteArray(payload) { (it + 5).toByte() }

    // Ключевой кадр с очков: VPS, SPS, PPS и IDR (19) — так пришло в пробе.
    private val keyFrame = nal(32) + nal(33, 6) + nal(34, 2) + nal(19, 40)
    private val delta = nal(1, 30)

    @Test
    fun `типы NAL по порядку, начальные коды 3 и 4 байта`() {
        assertEquals(listOf(32, 33, 34, 19), Hevc.nalTypes(keyFrame))
        assertEquals(listOf(1, 1), Hevc.nalTypes(nal(1, long = false) + nal(1)))
    }

    @Test
    fun `ключевой кадр — IRAP, обычный — нет`() {
        assertTrue(Hevc.isKeyFrame(keyFrame))
        assertTrue(Hevc.isKeyFrame(nal(21, 10))) // CRA
        assertFalse(Hevc.isKeyFrame(delta))
        assertFalse(Hevc.isKeyFrame(ByteArray(0)))
    }

    @Test
    fun `описание потока — VPS SPS PPS с начальными кодами, без кадра`() {
        assertArrayEquals(nal(32) + nal(33, 6) + nal(34, 2), Hevc.parameterSets(keyFrame))
        assertEquals(0, Hevc.parameterSets(delta).size)
    }

    @Test
    fun `SPS — отдельно, без начального кода`() {
        assertArrayEquals(nal(33, 6).copyOfRange(4, 4 + 2 + 6), Hevc.unit(keyFrame, Hevc.SPS))
        assertEquals(null, Hevc.unit(delta, Hevc.SPS))
    }
}
