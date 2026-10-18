package ru.vasev.glasses.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NoteSearchTest {

    private fun note(id: String, text: String, minute: Long) = Note(id, text, Instant.ofEpochSecond(minute * 60))

    private val notes = listOf(
        note("car", "Машина на парковке, уровень -2, место 45", 1),
        note("hotel", "Отель «Марина», номер 512", 2),
        note("keys", "Ключи от машины в синем рюкзаке", 3),
        note("en", "Parked the car near the blue gate", 4),
    )

    @Test
    fun `слово находится в любой форме`() {
        assertEquals(listOf("keys", "car"), NoteSearch.search(notes, "где моя машина", 5).map { it.id })
        assertEquals(listOf("keys", "car"), NoteSearch.search(notes, "машину", 5).map { it.id })
    }

    @Test
    fun `больше совпавших слов — выше`() {
        assertEquals("keys", NoteSearch.search(notes, "ключи машина", 5).first().id)
        assertEquals("car", NoteSearch.search(notes, "парковка машина", 5).first().id)
    }

    @Test
    fun `английские заметки — тоже`() {
        assertEquals(listOf("en"), NoteSearch.search(notes, "where is my car", 5).map { it.id })
        assertEquals(listOf("en"), NoteSearch.search(notes, "parking", 5).map { it.id })
    }

    @Test
    fun `пустой запрос — последние заметки`() {
        assertEquals(listOf("en", "keys"), NoteSearch.search(notes, "", 2).map { it.id })
    }

    @Test
    fun `ничего не нашлось — пусто, короткие слова не совпадают`() {
        assertTrue(NoteSearch.search(notes, "самолёт", 5).isEmpty())
        assertEquals(emptySet<String>(), NoteSearch.stems("в у я"))
    }

    @Test
    fun `основа — без двух последних букв, не короче трёх`() {
        assertEquals("маши", NoteSearch.stem("машина"))
        assertEquals("маши", NoteSearch.stem("машину"))
        assertEquals("car", NoteSearch.stem("cars"))
        assertEquals("клю", NoteSearch.stem("ключи"))
        assertEquals("ё", NoteSearch.stem("ё"))
    }
}
