package ru.vasev.glasses.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.WebSource

class ChatRecordsTest {

    @Test
    fun `обмен сохраняется и читается без потерь`() {
        val full = StoredExchange(
            asked = "Что это?\n\n[ссылки на снимке]",
            shown = "Что это?",
            summary = "Это вывеска.",
            details = "Подробно.",
            model = ModelTag("Sonnet 5.5", viaFallback = true, autoVision = false, noWebSearch = true),
            source = "GLASSES",
            photo = "1_ab.jpg",
            interrupted = true,
            silent = true,
            translation = true,
            sources = listOf(WebSource("https://example.com", "Пример")),
            notes = listOf(NoteRef("n1", saved = true), NoteRef("n2", saved = false)),
            deletedNotes = listOf("старая заметка"),
            ratesDate = "2026-11-02",
            ratesOffline = true,
            links = listOf(LinkRef("https://sber.ru", fromQr = true)),
        )
        assertEquals(full, ChatJson.exchange(ChatJson.exchange(full)))

        val bare = StoredExchange(asked = "Привет", summary = "Здравствуйте!")
        val back = ChatJson.exchange(ChatJson.exchange(bare))
        assertEquals(bare, back)
        assertNull(back.shown)
        assertEquals("Здравствуйте!", back.answer)
        assertEquals("Это вывеска.\n\nПодробно.", full.answer)
    }

    @Test
    fun `список чатов сохраняется и читается`() {
        val meta = ChatMeta("1-a", "Курс бата", 10L, 20L, fromGlasses = true, unread = true, searchText = "курс бата")
        assertEquals(meta, ChatJson.meta(ChatJson.meta(meta)))
    }

    @Test
    fun `новый чат после двух часов тишины`() {
        val hour = 60 * 60 * 1000L
        assertFalse(ChatRules.expired(updated = 0L, now = 2 * hour - 1))
        assertTrue(ChatRules.expired(updated = 0L, now = 2 * hour))
    }

    @Test
    fun `название — первая строка первого вопроса`() {
        assertEquals("Что это за растение?", ChatRules.title("  Что это за растение?\nи ещё строка"))
        val long = "Сколько стоит такси от аэропорта Суварнабхуми до отеля в Сиаме"
        val title = ChatRules.title(long)
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 41)
    }

    @Test
    fun `поиск по названию и тексту без учёта регистра`() {
        val chats = listOf(
            ChatMeta("1", "Маршрут до отеля", 0, 2, searchText = ChatRules.appendSearch("", "Маршрут до отеля", "Идите на север")),
            ChatMeta("2", "Меню в кафе", 0, 1, searchText = ChatRules.appendSearch("", "Меню в кафе", "Том ям — острый суп")),
        )
        assertEquals(listOf("1"), ChatRules.search(chats, "ОТЕЛ").map { it.id })
        assertEquals(listOf("2"), ChatRules.search(chats, "том ям").map { it.id })
        assertEquals(listOf("1", "2"), ChatRules.search(chats, "  ").map { it.id })
        assertTrue(ChatRules.search(chats, "бат").isEmpty())
    }
}
