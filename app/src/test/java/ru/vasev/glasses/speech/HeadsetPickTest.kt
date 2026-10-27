package ru.vasev.glasses.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetPickTest {

    private val buds = "Galaxy Buds Core"
    private val glasses = "RB Meta 0000"
    private fun pick(choice: MicChoice, vararg names: String) =
        HeadsetPick.pick(choice, names.toList()) { HeadsetPick.isGlasses(it, emptyList()) }

    @Test
    fun `очки узнаются по имени из DAT и по известному началу имени`() {
        assertTrue(HeadsetPick.isGlasses("My glasses", listOf("my glasses")))
        assertTrue(HeadsetPick.isGlasses(glasses, emptyList()))
        assertTrue(HeadsetPick.isGlasses("Oakley Meta HSTN 01", emptyList()))
        assertFalse(HeadsetPick.isGlasses(buds, listOf(glasses)))
        assertFalse(HeadsetPick.isGlasses("", listOf("")))
    }

    @Test
    fun `«Очки» — только очки, другую гарнитуру не берём`() {
        assertEquals(glasses, pick(MicChoice.GLASSES, buds, glasses))
        assertNull(pick(MicChoice.GLASSES, buds))
    }

    @Test
    fun `«Авто» и «Гарнитура» — очки в приоритете, иначе любая`() {
        assertEquals(glasses, pick(MicChoice.AUTO, buds, glasses))
        assertEquals(glasses, pick(MicChoice.HEADSET, buds, glasses))
        assertEquals(buds, pick(MicChoice.AUTO, buds))
        assertNull(pick(MicChoice.AUTO))
    }

    @Test
    fun `«Телефон» — гарнитуру не берём`() {
        assertNull(pick(MicChoice.PHONE, glasses, buds))
    }
}
