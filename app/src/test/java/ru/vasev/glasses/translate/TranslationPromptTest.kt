package ru.vasev.glasses.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptTest {

    @Test
    fun `without history only direction and phrase`() {
        val prompt = TranslationPrompt.build(PartnerLanguage.TURKISH, Who.PARTNER, "  Merhaba!  ", emptyList())
        assertEquals("Переведи с турецкого на русский:\nMerhaba!", prompt)
    }

    @Test
    fun `my reply is translated into partner language`() {
        val prompt = TranslationPrompt.build(PartnerLanguage.GERMAN, Who.ME, "Сколько стоит?", emptyList())
        assertEquals("Переведи с русского на немецкий:\nСколько стоит?", prompt)
    }

    @Test
    fun `only last three lines go into context in order with speaker labels`() {
        val history = listOf(
            Line(Who.PARTNER, "one", "один"),
            Line(Who.ME, "два", "two"),
            Line(Who.PARTNER, "three", "три"),
            Line(Who.ME, "четыре", "four"),
        )
        val prompt = TranslationPrompt.build(PartnerLanguage.ENGLISH, Who.PARTNER, "five", history)
        val expected = """
            Предыдущие реплики разговора — только для понимания контекста, их не переводи:
            Я: два (перевод: two)
            Собеседник: three (перевод: три)
            Я: четыре (перевод: four)

            Переведи с английского на русский:
            five
        """.trimIndent()
        assertEquals(expected, prompt)
        assertFalse(prompt.contains("one"))
    }

    @Test
    fun `line without translation is passed as original only`() {
        val history = listOf(Line(Who.PARTNER, "สวัสดี", null))
        val prompt = TranslationPrompt.build(PartnerLanguage.THAI, Who.ME, "Привет", history)
        assertTrue(prompt.contains("Собеседник: สวัสดี\n"))
        assertTrue(prompt.endsWith("Переведи с русского на тайский:\nПривет"))
    }
}
