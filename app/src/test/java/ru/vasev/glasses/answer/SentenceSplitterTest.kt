package ru.vasev.glasses.answer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SentenceSplitterTest {

    // Тот же список, что в приложении; рабочая папка unit-тестов — модуль app.
    private val abbreviations = SentenceSplitter.parseAbbreviations(File("src/main/res/raw/abbreviations.txt").readText())

    /** Весь текст целиком и тот же текст по одному символу должны резаться одинаково. */
    private fun split(text: String): List<String> {
        val whole = SentenceSplitter(abbreviations).let { it.feed(text) + it.finish() }
        val byChar = SentenceSplitter(abbreviations).let { s -> text.flatMap { s.feed(it.toString()) } + s.finish() }
        assertEquals("поток по символу", whole, byChar)
        return whole
    }

    @Test
    fun `simple sentences`() {
        assertEquals(listOf("Привет!", "Как дела?", "Всё хорошо."), split("Привет! Как дела? Всё хорошо."))
    }

    @Test
    fun `numbers and prices are not boundaries`() {
        assertEquals(
            listOf("Версия 3.5 вышла 12.10.2026.", "Стоит $4.99 в месяц."),
            split("Версия 3.5 вышла 12.10.2026. Стоит $4.99 в месяц."),
        )
    }

    @Test
    fun `russian abbreviations`() {
        assertEquals(
            listOf("Это т. е. просто пример, см. ниже.", "Идите на ул. Ленина, д. 5.", "Готово."),
            split("Это т. е. просто пример, см. ниже. Идите на ул. Ленина, д. 5. Готово."),
        )
        assertEquals(listOf("Фрукты, овощи и т. д.", "Дальше."), split("Фрукты, овощи и т. д. Дальше."))
    }

    @Test
    fun `english abbreviations`() {
        assertEquals(
            listOf("Fruits, e.g. apples, are healthy.", "Ask Dr. Smith on Main St. tomorrow."),
            split("Fruits, e.g. apples, are healthy. Ask Dr. Smith on Main St. tomorrow."),
        )
    }

    @Test
    fun `initials`() {
        assertEquals(listOf("Стихи написал А. С. Пушкин.", "Он поэт."), split("Стихи написал А. С. Пушкин. Он поэт."))
    }

    @Test
    fun `ellipsis, quotes and newlines`() {
        assertEquals(
            listOf("Ну… посмотрим.", "Он сказал: «Да!»", "Первая строка", "Вторая строка."),
            split("Ну… посмотрим. Он сказал: «Да!» Первая строка\nВторая строка."),
        )
    }

    @Test
    fun `sentence waits for what comes after the dot`() {
        val s = SentenceSplitter(abbreviations)
        assertEquals(emptyList<String>(), s.feed("Цена 3."))
        assertEquals(emptyList<String>(), s.feed("5 евро"))
        assertEquals(listOf("Цена 3.5 евро."), s.feed(". Дальше"))
        assertEquals(listOf("Дальше"), s.finish())
    }
}
