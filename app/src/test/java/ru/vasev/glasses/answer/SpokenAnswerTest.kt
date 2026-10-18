package ru.vasev.glasses.answer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SpokenAnswerTest {

    private val abbreviations = SentenceSplitter.parseAbbreviations(File("src/main/res/raw/abbreviations.txt").readText())
    private val marker = SpokenAnswer.MARKER

    private fun run(deltas: List<String>): Pair<SpokenAnswer, List<String>> {
        val answer = SpokenAnswer(SentenceSplitter(abbreviations))
        val spoken = deltas.flatMap(answer::feed) + answer.finish()
        return answer to spoken
    }

    @Test
    fun `summary is spoken, details only shown`() {
        val (a, spoken) = run(listOf("Небо голубое из-за рассеяния. Синий свет рассеивается сильнее.\n$marker\nЭто называется рэлеевским рассеянием."))
        assertEquals(listOf("Небо голубое из-за рассеяния.", "Синий свет рассеивается сильнее."), spoken)
        assertEquals("Небо голубое из-за рассеяния. Синий свет рассеивается сильнее.", a.summaryText)
        assertEquals("Это называется рэлеевским рассеянием.", a.detailsText)
        assertFalse(a.fullText.contains(marker))
    }

    @Test
    fun `marker split across deltas`() {
        val text = "Коротко: да. $marker Подробно: потому что."
        // Поток по одному символу — маркер приходит частями.
        val (a, spoken) = run(text.map { it.toString() })
        assertEquals(listOf("Коротко: да."), spoken)
        assertEquals("Подробно: потому что.", a.detailsText)
        assertFalse(a.summaryText.contains("⟦"))
    }

    @Test
    fun `first sentence is ready before the stream ends`() {
        val answer = SpokenAnswer(SentenceSplitter(abbreviations))
        assertEquals(emptyList<String>(), answer.feed("Арбуз выбирают по звуку"))
        assertEquals(listOf("Арбуз выбирают по звуку."), answer.feed(". Спелый звенит"))
    }

    @Test
    fun `without marker only first sentences are spoken`() {
        val (a, spoken) = run(listOf("Один. Два. Три. Четыре. Пять."))
        assertEquals(listOf("Один.", "Два.", "Три."), spoken)
        assertEquals("Четыре. Пять.", a.detailsText)
    }

    @Test
    fun `answer without details`() {
        val (a, spoken) = run(listOf("Да, ", "конечно."))
        assertEquals(listOf("Да, конечно."), spoken)
        assertEquals("", a.detailsText)
        assertEquals("Да, конечно.", a.fullText)
    }
}
