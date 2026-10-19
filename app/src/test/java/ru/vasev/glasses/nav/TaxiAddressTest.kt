package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiAddressTest {

    @Test
    fun `язык адреса по стране цели`() {
        assertEquals("th", CountryLanguage.forCountry("TH"))
        assertEquals("ar", CountryLanguage.forCountry("ae"))
        assertEquals("ru", CountryLanguage.forCountry("KZ"))
        assertEquals("en", CountryLanguage.forCountry("GB"))
        assertNull(CountryLanguage.forCountry(null))
        assertNull(CountryLanguage.forCountry("AQ"))
        assertEquals("Thai", CountryLanguage.englishName("th"))
    }

    @Test
    fun `адрес написан письмом нужного языка`() {
        assertTrue(CountryLanguage.scriptMatches("ซอยสุขุมวิท 20 แขวงคลองเตย", "th"))
        // Геокодер не нашёл тайского и отдал латиницу — таксисту это не поможет.
        assertFalse(CountryLanguage.scriptMatches("Soi Sukhumvit 20, Khlong Toei", "th"))
        assertTrue(CountryLanguage.scriptMatches("東京都千代田区丸の内1丁目", "ja"))
        assertTrue(CountryLanguage.scriptMatches("Calle Mayor 5, Madrid", "es"))
        assertFalse(CountryLanguage.scriptMatches("12, 34", "ka"))
    }

    @Test
    fun `plus code по спецификации`() {
        // Пример из документации Open Location Code: Googleplex.
        assertEquals("849VCWC8+R9", PlusCode.encode(GeoPoint(37.4220625, -122.0841094)))
        assertEquals(11, PlusCode.encode(GeoPoint(-33.8688, 151.2093)).length)
        assertEquals('+', PlusCode.encode(GeoPoint(90.0, 180.0))[8])
    }

    @Test
    fun `ссылки такси — только документированные`() {
        val to = GeoPoint(13.7563, 100.5018)
        assertEquals(
            "https://3.redirect.appmetrica.yandex.com/route?end-lat=13.756300&end-lon=100.501800&ref=ru.vasev.glasses&appmetrica_tracking_id=1178268795219780156",
            TaxiLinks.destination(TaxiApp.YANDEX_GO, to, "Отель"),
        )
        assertEquals(
            "uber://?action=setPickup&pickup=my_location&dropoff%5Blatitude%5D=13.756300&dropoff%5Blongitude%5D=100.501800&dropoff%5Bnickname%5D=My%20hotel",
            TaxiLinks.destination(TaxiApp.UBER, to, "My hotel"),
        )
        assertNull(TaxiLinks.destination(TaxiApp.GRAB, to, "x"))
    }
}
