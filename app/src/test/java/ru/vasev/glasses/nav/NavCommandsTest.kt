package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.vasev.glasses.places.PlaceKind

class NavCommandsTest {

    private val v = NavVocabulary(
        goPrefixes = listOf("веди в", "веди к", "веди до", "как добраться до", "take me to"),
        returnPhrases = listOf("как вернуться", "как вернуться назад", "веди обратно"),
        backToStartPhrases = listOf("назад к началу маршрута", "вернуться к началу маршрута"),
        taxiPhrases = listOf("покажи адрес таксисту"),
        howFarPhrases = listOf("сколько осталось", "сколько ещё осталось"),
        whereCarPhrases = listOf("где машина", "где моя машина"),
        rememberTriggers = listOf("запомни"),
        rememberPlace = mapOf(
            PlaceKind.HOTEL to listOf("это мой отель", "мой отель"),
            PlaceKind.CAR to listOf("здесь машина", "я припарковался"),
        ),
    )

    @Test
    fun `команды целиком, без учёта регистра, знаков и ё`() {
        assertEquals(NavCommand.Return, NavCommands.parse("Как вернуться?", v))
        assertEquals(NavCommand.BackToStart, NavCommands.parse("Назад к началу маршрута", v))
        assertEquals(NavCommand.ShowTaxi, NavCommands.parse("Покажи адрес таксисту!", v))
        assertEquals(NavCommand.HowFar, NavCommands.parse("Сколько ещё осталось?", v))
        assertEquals(NavCommand.HowFar, NavCommands.parse("сколько еще осталось", v))
        assertEquals(NavCommand.GoToPlace(PlaceKind.CAR), NavCommands.parse("Где моя машина?", v))
    }

    @Test
    fun `веди в — цель после начала фразы`() {
        assertEquals(NavCommand.GoTo("отель"), NavCommands.parse("Веди в отель", v))
        assertEquals(NavCommand.GoTo("Эрмитажа"), NavCommands.parse("как добраться до Эрмитажа?", v))
        assertEquals(NavCommand.GoTo("Wat Arun"), NavCommands.parse("Take me to Wat Arun", v))
    }

    @Test
    fun `запомни место — только с фразой места, остальное — обычная заметка`() {
        assertEquals(NavCommand.RememberPlace(PlaceKind.HOTEL), NavCommands.parse("Запомни, это мой отель", v))
        assertEquals(NavCommand.RememberPlace(PlaceKind.CAR), NavCommands.parse("запомни: я припарковался", v))
        assertNull(NavCommands.parse("Запомни, ключи в синем рюкзаке", v))
    }

    @Test
    fun `обычные вопросы — не команды`() {
        assertNull(NavCommands.parse("Как вернуться к теме разговора о погоде?", v))
        assertNull(NavCommands.parse("Что я вижу", v))
        assertNull(NavCommands.parse("веди", v))
        assertNull(NavCommands.parse("", v))
    }
}
