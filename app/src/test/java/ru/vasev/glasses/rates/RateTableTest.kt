package ru.vasev.glasses.rates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RateTableTest {

    /** Фрагмент ответа cbr.ru/scripts/XML_daily.asp (формат зеркала cbr-xml-daily.ru тот же). */
    private val xml = """
        <?xml version="1.0" encoding="windows-1251"?>
        <ValCurs Date="18.10.2026" name="Foreign Currency Market">
        <Valute ID="R01235"><NumCode>840</NumCode><CharCode>USD</CharCode><Nominal>1</Nominal><Name>Доллар США</Name><Value>80,5000</Value><VunitRate>80,5</VunitRate></Valute>
        <Valute ID="R01239"><NumCode>978</NumCode><CharCode>EUR</CharCode><Nominal>1</Nominal><Name>Евро</Name><Value>94,2500</Value><VunitRate>94,25</VunitRate></Valute>
        <Valute ID="R01700J"><NumCode>949</NumCode><CharCode>TRY</CharCode><Nominal>10</Nominal><Name>Турецких лир</Name><Value>19,8000</Value><VunitRate>1,98</VunitRate></Valute>
        <Valute ID="R01820"><NumCode>392</NumCode><CharCode>JPY</CharCode><Nominal>100</Nominal><Name>Японских иен</Name><Value>53,1000</Value><VunitRate>0,531</VunitRate></Valute>
        </ValCurs>
    """.trimIndent()

    private val table = RateTable.parseCbrXml(xml)

    @Test
    fun `дата и курсы за единицу с учётом номинала и запятой`() {
        assertEquals(LocalDate.of(2026, 10, 18), table.date)
        assertEquals(80.5, table.rubPerUnit.getValue("USD"), 1e-9)
        assertEquals(1.98, table.rubPerUnit.getValue("TRY"), 1e-9)
        assertEquals(0.531, table.rubPerUnit.getValue("JPY"), 1e-9)
    }

    @Test
    fun `в рубли и кросс-курс через рубль`() {
        assertEquals(1885.0, table.convert(20.0, "EUR", "RUB")!!, 1e-9)
        assertEquals(1.0, table.rate("RUB", "RUB")!!, 1e-9)
        // 100 лир в евро: 100 × 1,98 / 94,25.
        assertEquals(100 * 1.98 / 94.25, table.convert(100.0, "TRY", "EUR")!!, 1e-9)
        assertEquals(80.5 / 94.25, table.rate("usd", "eur")!!, 1e-9)
    }

    @Test
    fun `валюты нет у ЦБ — null`() {
        assertNull(table.rate("XYZ", "RUB"))
        assertNull(table.convert(1.0, "RUB", "XYZ"))
        assertTrue(table.has("RUB"))
        assertFalse(table.has("XYZ"))
    }

    @Test
    fun `кеш сохраняет курсы без потерь`() {
        assertEquals(table, RateTable.fromJson(table.toJson()))
    }

    @Test(expected = Exception::class)
    fun `чужой ответ (страница с ошибкой) — исключение`() {
        RateTable.parseCbrXml("<html><body>Service unavailable</body></html>")
    }

    @Test
    fun `валюта страны`() {
        assertEquals("TRY", CurrencySettings.forCountry("TR"))
        assertEquals("EUR", CurrencySettings.forCountry("de"))
        assertEquals("RUB", CurrencySettings.forCountry("RU"))
    }
}
