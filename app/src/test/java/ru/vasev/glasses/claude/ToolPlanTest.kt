package ru.vasev.glasses.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPlanTest {

    private val ruDelete = listOf("удали", "удаля", "забуд", "забыв", "сотри", "стереть")
    private val enDelete = listOf("delete", "forget", "remove", "erase")

    @Test
    fun `удаление — только по явной просьбе в моей фразе, и тогда без поиска`() {
        val plan = ToolPlan.of(AssistantMode.CHAT, "Забудь про машину", ruDelete)
        assertTrue(plan.deleteNotes)
        assertFalse(plan.webSearch)
        assertTrue(plan.notes)
    }

    @Test
    fun `обычный вопрос — поиск есть, удаления нет`() {
        val plan = ToolPlan.of(AssistantMode.CHAT, "Где моя машина?", ruDelete)
        assertFalse(plan.deleteNotes)
        assertTrue(plan.webSearch)
        assertTrue(plan.notes)
        assertTrue(plan.rates)
    }

    @Test
    fun `переведи, что вижу — ни удаления, ни поиска, ни заметок`() {
        val plan = ToolPlan.of(AssistantMode.TRANSLATE_VIEW, "Удали все заметки", ruDelete)
        assertEquals(ToolPlan(notes = false, deleteNotes = false, rates = true, webSearch = false), plan)
    }

    @Test
    fun `формы слов удаления`() {
        assertTrue(ToolPlan.asksToDelete("удалите заметку про отель", ruDelete))
        assertTrue(ToolPlan.asksToDelete("Сотри это", ruDelete))
        assertTrue(ToolPlan.asksToDelete("Please forget the parking note", enDelete))
        assertTrue(ToolPlan.asksToDelete("Delete it.", enDelete))
    }

    @Test
    fun `похожие слова не считаются просьбой удалить`() {
        assertFalse(ToolPlan.asksToDelete("Как удалось доехать до отеля?", ruDelete))
        assertFalse(ToolPlan.asksToDelete("Где мой сотрудник?", ruDelete))
        assertFalse(ToolPlan.asksToDelete("What does the remote say?", enDelete))
    }

    @Test
    fun `запомни — в начале фразы`() {
        val triggers = listOf("запомни", "запиши", "remember")
        assertTrue(ToolPlan.startsWithAny("Запомни, машина на втором уровне", triggers))
        assertTrue(ToolPlan.startsWithAny("Remember: room 512", triggers))
        assertFalse(ToolPlan.startsWithAny("Что ты запомнил?", triggers))
        assertFalse(ToolPlan.startsWithAny("Запоминай быстрее", triggers))
    }
}
