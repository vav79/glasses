package ru.vasev.glasses.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrepTest {

    @Test
    fun `long side is reduced to 1024, proportions kept`() {
        assertEquals(1024 to 768, ImagePrep.scaledSize(4000, 3000))
        assertEquals(768 to 1024, ImagePrep.scaledSize(3000, 4000))
        assertEquals(1024 to 576, ImagePrep.scaledSize(1920, 1080))
    }

    @Test
    fun `small images are not upscaled`() {
        assertEquals(896 to 504, ImagePrep.scaledSize(896, 504))
        assertEquals(1024 to 1024, ImagePrep.scaledSize(1024, 1024))
    }

    @Test
    fun `extreme panorama keeps at least one pixel`() {
        assertEquals(1024 to 1, ImagePrep.scaledSize(100_000, 10))
    }

    @Test
    fun `exif detection finds APP1 Exif segment`() {
        // SOI, APP1 marker, length, "Exif\0\0", начало TIFF-заголовка
        val withExif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10) +
            "Exif".toByteArray() + byteArrayOf(0, 0, 0x4D, 0x4D)
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) +
            "JFIF".toByteArray() + byteArrayOf(0, 1)
        assertTrue(ImagePrep.hasExif(withExif))
        assertFalse(ImagePrep.hasExif(plain))
        assertFalse(ImagePrep.hasExif(ByteArray(0)))
    }

    @Test
    fun `almost black frame - phone in pocket`() {
        val black = IntArray(1000) { 0xFF050505.toInt() }
        assertTrue(ImagePrep.isDark(black))
        // Немного шума и пара светлых точек (подсветка экрана сквозь ткань) — всё ещё «тёмный».
        val noisy = IntArray(1000) { if (it % 100 == 0) 0xFFFFFFFF.toInt() else 0xFF0A0A0A.toInt() }
        assertTrue(ImagePrep.isDark(noisy))
    }

    @Test
    fun `dim but real scene is kept`() {
        // Вечерняя парковка: в основном темно, но фонари и разметка видны.
        val evening = IntArray(1000) { if (it % 10 == 0) 0xFFC8C8B4.toInt() else 0xFF141414.toInt() }
        assertFalse(ImagePrep.isDark(evening))
        assertFalse(ImagePrep.isDark(IntArray(1000) { 0xFF808080.toInt() }))
    }
}
