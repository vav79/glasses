package ru.vasev.glasses.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    private val white = Contrast.Rgb(1.0, 1.0, 1.0)
    private val black = Contrast.Rgb(0.0, 0.0, 0.0)

    @Test
    fun `wcag reference values`() {
        assertEquals(21.0, Contrast.ratio(black, white), 0.01)
        assertEquals(1.0, Contrast.ratio(white, white), 0.001)
        // #767676 на белом — классическая граница 4.5:1.
        assertEquals(4.54, Contrast.ratio(Contrast.Rgb.of(0xFF767676), white), 0.01)
    }

    @Test
    fun `tint alpha is the smallest that keeps 4_5 against every backdrop`() {
        val text = Contrast.Rgb.of(0xFF1B1B1F) // тёмный текст
        val tint = Contrast.Rgb.of(0xFFFBF8FF) // светлое стекло
        // Позади — и светлый фон, и тёмное пятно.
        val behind = listOf(Contrast.Rgb.of(0xFFE8E0F0), Contrast.Rgb.of(0xFF303040))
        val alpha = Contrast.minTintAlpha(text, tint, behind)
        assertTrue(Contrast.worst(text, tint, alpha, behind) >= Contrast.AA)
        // На шаг прозрачнее — уже не хватает (над тёмным пятном).
        assertTrue(Contrast.worst(text, tint, alpha - 0.01, behind) < Contrast.AA)
    }

    @Test
    fun `floor is respected when contrast is already fine`() {
        val alpha = Contrast.minTintAlpha(black, white, listOf(white), floor = 0.14)
        assertEquals(0.14, alpha, 1e-9)
    }

    @Test
    fun `accent is darkened toward text only as much as needed`() {
        val text = Contrast.Rgb.of(0xFF1B1B1F)
        val tint = white
        val paleAccent = Contrast.Rgb.of(0xFF8AA4F0) // светлый primary из обоев — меньше 4.5:1 на белом
        val behind = listOf(white)
        assertTrue(Contrast.ratio(paleAccent, white) < Contrast.AA)
        val k = Contrast.minMixToward(paleAccent, text, tint, 1.0, behind)
        assertTrue(k > 0.0 && k < 1.0)
        // Уже подходящий цвет не трогается.
        assertEquals(0.0, Contrast.minMixToward(text, black, tint, 1.0, behind), 1e-9)
    }

    @Test
    fun `impossible target returns full opacity`() {
        val grey = Contrast.Rgb.of(0xFF999999)
        assertEquals(1.0, Contrast.minTintAlpha(grey, Contrast.Rgb.of(0xFFAAAAAA), listOf(black, white)), 1e-9)
    }
}
