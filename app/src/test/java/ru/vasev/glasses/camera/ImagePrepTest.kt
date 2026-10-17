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
}
