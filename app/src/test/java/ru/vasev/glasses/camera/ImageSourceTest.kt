package ru.vasev.glasses.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSourceTest {

    @Test
    fun `glasses by default`() {
        assertEquals(ImageSource.GLASSES, ImageSource.restore(null, debug = false))
        assertEquals(ImageSource.GLASSES, ImageSource.restore(null, debug = true))
    }

    @Test
    fun `simulator only in debug build`() {
        assertFalse(ImageSource.SIMULATOR in ImageSource.available(debug = false))
        assertTrue(ImageSource.SIMULATOR in ImageSource.available(debug = true))
        assertEquals(
            listOf(ImageSource.GLASSES, ImageSource.PHONE_CAMERA),
            ImageSource.available(debug = false),
        )
    }

    @Test
    fun `saved simulator in release falls back to glasses`() {
        assertEquals(ImageSource.GLASSES, ImageSource.restore("SIMULATOR", debug = false))
        assertEquals(ImageSource.SIMULATOR, ImageSource.restore("SIMULATOR", debug = true))
        assertEquals(ImageSource.PHONE_CAMERA, ImageSource.restore("PHONE_CAMERA", debug = false))
        assertEquals(ImageSource.GLASSES, ImageSource.restore("WHATEVER", debug = false))
        // Галерея больше не источник, а плитка в ленте снимков: сохранённый выбор — на источник по умолчанию.
        assertEquals(ImageSource.GLASSES, ImageSource.restore("GALLERY", debug = false))
    }

    @Test
    fun `log names and glasses flag`() {
        assertEquals(listOf("glasses", "phone", "gallery", "simulator"), ImageSource.entries.map { it.logName })
        assertTrue(ImageSource.GLASSES.usesGlasses && ImageSource.SIMULATOR.usesGlasses)
        assertFalse(ImageSource.PHONE_CAMERA.usesGlasses || ImageSource.GALLERY.usesGlasses)
    }
}
