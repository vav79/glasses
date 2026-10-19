package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import java.time.Instant

class NavQueryTest {

    private val prefixes = listOf("как добраться до", "как добраться", "веди в", "веди до", "take me to", "how do i get to")
    private val aliases = mapOf(
        PlaceKind.HOTEL to listOf("отель", "в отель", "гостиница", "hotel", "the hotel"),
        PlaceKind.HOME to listOf("домой", "дом", "home"),
        PlaceKind.CAR to listOf("машина", "к машине", "car"),
    )

    private fun place(kind: PlaceKind, name: String = "") = Place("id-$kind-$name", kind, name, GeoPoint(1.0, 2.0), null, Instant.EPOCH)

    @Test
    fun `убирает начало фразы`() {
        assertEquals("вокзала", NavQuery.stripPrefix("Как добраться до вокзала?", prefixes))
        assertEquals("отель", NavQuery.stripPrefix("веди в отель", prefixes))
        assertEquals("Wat Arun", NavQuery.stripPrefix("take me to Wat Arun", prefixes))
        assertEquals("Ленина 5", NavQuery.stripPrefix("Ленина 5", prefixes))
        // Фраза целиком равна началу — ничего не отрезаем.
        assertEquals("как добраться", NavQuery.stripPrefix("как добраться", prefixes))
    }

    @Test
    fun `моё место по названию вида и своему названию`() {
        val hotel = place(PlaceKind.HOTEL)
        val gym = place(PlaceKind.CUSTOM, "Спортзал")
        val places = listOf(hotel, gym)
        assertEquals(hotel, NavQuery.matchPlace("Отель", places, aliases))
        assertEquals(hotel, NavQuery.matchPlace("в отель", places, aliases))
        assertEquals(gym, NavQuery.matchPlace("спортзал", places, aliases))
        assertNull(NavQuery.matchPlace("домой", places, aliases))
        assertNull(NavQuery.matchPlace("отель Марина", places, aliases))
    }

    @Test
    fun `вид места без сохранённого места`() {
        assertEquals(PlaceKind.HOME, NavQuery.kindOf("Домой", aliases))
        assertNull(NavQuery.kindOf("вокзал", aliases))
    }
}
