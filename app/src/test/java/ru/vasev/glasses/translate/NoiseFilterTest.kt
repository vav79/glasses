package ru.vasev.glasses.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFilterTest {

    @Test
    fun `empty and punctuation-only phrases are noise`() {
        listOf("", "   ", "...", "?!", " - ").forEach { assertTrue("«$it»", NoiseFilter.isNoise(it)) }
    }

    @Test
    fun `one or two filler sounds are noise`() {
        listOf("э", "Ээээ...", "эм хм", "Ммм", "uh", "Hmmm?", "um, uh", "äh", "euh", "şey", "嗯", "嗯嗯", "啊", "อืม", "a", "я")
            .forEach { assertTrue("«$it»", NoiseFilter.isNoise(it)) }
    }

    @Test
    fun `short meaningful answers pass`() {
        listOf("да", "Нет.", "ok", "no", "是", "好", "evet", "ja", "oui", "sí", "نعم", "ใช่", "5")
            .forEach { assertFalse("«$it»", NoiseFilter.isNoise(it)) }
    }

    @Test
    fun `real phrases pass even if they start with a filler`() {
        listOf("э, сколько это стоит?", "uh where is the station", "Merhaba, nasılsınız?", "这个多少钱", "ราคาเท่าไหร่")
            .forEach { assertFalse("«$it»", NoiseFilter.isNoise(it)) }
    }

    @Test
    fun `three fillers are not filtered`() {
        // Фильтр — только для одного-двух звуков; длинное «э-м-м» уже решает переводчик.
        assertFalse(NoiseFilter.isNoise("э эм хм"))
    }
}
