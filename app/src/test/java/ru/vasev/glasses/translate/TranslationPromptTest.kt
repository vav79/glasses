package ru.vasev.glasses.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vasev.glasses.i18n.AppLanguage

class TranslationPromptTest {

    private val ru = PartnerLanguage.RUSSIAN
    private val en = PartnerLanguage.ENGLISH

    @Test
    fun `without history only direction and phrase`() {
        val prompt = TranslationPrompt.build(PartnerLanguage.TURKISH, ru, Who.PARTNER, "  Merhaba!  ", emptyList())
        assertEquals("Translate from Turkish into Russian:\nMerhaba!", prompt)
    }

    @Test
    fun `my reply is translated into partner language`() {
        val prompt = TranslationPrompt.build(PartnerLanguage.GERMAN, ru, Who.ME, "Сколько стоит?", emptyList())
        assertEquals("Translate from Russian into German:\nСколько стоит?", prompt)
    }

    @Test
    fun `my language follows the app language`() {
        val prompt = TranslationPrompt.build(PartnerLanguage.TURKISH, en, Who.PARTNER, "Merhaba", emptyList())
        assertEquals("Translate from Turkish into English:\nMerhaba", prompt)
        // Английский интерфейс: собеседник может говорить по-русски.
        val fromRussian = TranslationPrompt.build(ru, en, Who.PARTNER, "Привет", emptyList())
        assertEquals("Translate from Russian into English:\nПривет", fromRussian)
    }

    @Test
    fun `only last three lines go into context in order with speaker labels`() {
        val history = listOf(
            Line(Who.PARTNER, "one", "один"),
            Line(Who.ME, "два", "two"),
            Line(Who.PARTNER, "three", "три"),
            Line(Who.ME, "четыре", "four"),
        )
        val prompt = TranslationPrompt.build(en, ru, Who.PARTNER, "five", history)
        val expected = """
            Earlier lines of the conversation — for context only, do not translate them:
            Me: два (translation: two)
            Partner: three (translation: три)
            Me: четыре (translation: four)

            Translate from English into Russian:
            five
        """.trimIndent()
        assertEquals(expected, prompt)
        assertFalse(prompt.contains("one"))
    }

    @Test
    fun `line without translation is passed as original only`() {
        val history = listOf(Line(Who.PARTNER, "สวัสดี", null))
        val prompt = TranslationPrompt.build(PartnerLanguage.THAI, ru, Who.ME, "Привет", history)
        assertTrue(prompt.contains("Partner: สวัสดี\n"))
        assertTrue(prompt.endsWith("Translate from Russian into Thai:\nПривет"))
    }

    @Test
    fun `partner list excludes my language`() {
        assertEquals(ru, PartnerLanguage.forApp(AppLanguage.RUSSIAN))
        assertEquals(en, PartnerLanguage.forApp(AppLanguage.ENGLISH))
        assertFalse(ru in PartnerLanguage.partnersFor(ru))
        assertTrue(en in PartnerLanguage.partnersFor(ru))
        assertFalse(en in PartnerLanguage.partnersFor(en))
        assertTrue(ru in PartnerLanguage.partnersFor(en))
        assertEquals(PartnerLanguage.entries.size - 1, PartnerLanguage.partnersFor(en).size)
    }

    @Test
    fun `saved partner equal to my language is replaced`() {
        // Был английский собеседник, интерфейс переключили на английский — собеседник уже не может быть английским.
        assertEquals(PartnerLanguage.partnersFor(en).first(), PartnerLanguage.resolvePartner(en, en))
        assertEquals(PartnerLanguage.TURKISH, PartnerLanguage.resolvePartner(PartnerLanguage.TURKISH, en))
        assertEquals(PartnerLanguage.partnersFor(ru).first(), PartnerLanguage.resolvePartner(null, ru))
    }
}
