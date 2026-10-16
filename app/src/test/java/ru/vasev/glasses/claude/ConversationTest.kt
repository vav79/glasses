package ru.vasev.glasses.claude

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    private val photoA = byteArrayOf(1)
    private val photoB = byteArrayOf(2)

    private fun Conversation.addPlain(n: Int) = repeat(n) { add("q$it", null, "a$it") }

    private fun List<Turn>.users() = filterIsInstance<Turn.User>()

    @Test
    fun `history goes before the new question`() {
        val c = Conversation()
        c.add("Привет", null, "Здравствуйте")
        val turns = c.turnsFor("Как дела?")
        assertEquals(
            listOf(Turn.User("Привет"), Turn.Assistant("Здравствуйте"), Turn.User("Как дела?")),
            turns,
        )
    }

    @Test
    fun `window is trimmed in a batch to the last five when ten exchanges collect`() {
        val c = Conversation()
        c.addPlain(9)
        assertEquals(9, c.remembered.size)
        assertEquals(0, c.memoryStart)

        c.add("q9", null, "a9")
        assertEquals(5, c.remembered.size)
        assertEquals(listOf("q5", "q6", "q7", "q8", "q9"), c.remembered.map { it.question })
        assertEquals(10, c.exchanges.size)

        // Следующие обмены копятся без обрезки до новой десятки.
        c.add("q10", null, "a10")
        assertEquals(6, c.remembered.size)
        assertEquals(5, c.memoryStart)
    }

    @Test
    fun `forgotten exchanges are not sent`() {
        val c = Conversation()
        c.addPlain(10)
        val questions = c.turnsFor("new").users().map { it.text }
        assertEquals(listOf("q5", "q6", "q7", "q8", "q9", "new"), questions)
    }

    @Test
    fun `follow-up question keeps the last photo as an image`() {
        val c = Conversation()
        c.add("Что на вывеске?", photoA, "Кофе сто пятьдесят рублей")
        val first = c.turnsFor("А сколько это стоит?").users().first()
        assertArrayEquals(photoA, first.jpeg)
        assertFalse(first.photoPlaceholder)
    }

    @Test
    fun `new photo replaces older photos with placeholder`() {
        val c = Conversation()
        c.add("one", photoA, "a1")
        val users = c.turnsFor("two", photoB).users()
        assertNull(users[0].jpeg)
        assertTrue(users[0].photoPlaceholder)
        assertArrayEquals(photoB, users[1].jpeg)
    }

    @Test
    fun `only the most recent remembered photo is kept`() {
        val c = Conversation()
        c.add("one", photoA, "a1")
        c.add("two", photoB, "a2")
        c.add("three", null, "a3")
        val users = c.turnsFor("four").users()
        assertTrue(users[0].photoPlaceholder)
        assertArrayEquals(photoB, users[1].jpeg)
        assertNull(users[2].jpeg)
        assertFalse(users[2].photoPlaceholder)
    }

    @Test
    fun `clear forgets everything`() {
        val c = Conversation()
        c.addPlain(12)
        c.clear()
        assertTrue(c.isEmpty)
        assertEquals(0, c.memoryStart)
        assertEquals(listOf(Turn.User("hi")), c.turnsFor("hi"))
    }

    @Test
    fun `reset commands are recognized`() {
        listOf("новый разговор", "Новый разговор.", "НОВЫЙ  РАЗГОВОР!", "забудь всё", "Забудь все", "забудь всё!")
            .forEach { assertTrue(it, Conversation.isResetCommand(it)) }
        listOf("расскажи про новый разговор", "забудь", "новый", "что я вижу")
            .forEach { assertFalse(it, Conversation.isResetCommand(it)) }
    }
}
