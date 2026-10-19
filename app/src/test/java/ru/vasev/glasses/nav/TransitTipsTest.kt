package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitTipsTest {

    @Test
    fun `строки советов — и с переносами, и склеенные после поиска`() {
        assertEquals(
            listOf("• MRT до Sanam Chai, затем пешком", "• Билет 17–45 бат, оплата картой"),
            TransitTips.bullets("• MRT до Sanam Chai, затем пешком\n• Билет 17–45 бат, оплата картой"),
        )
        // Вступление до первого «•» и markdown — убираются; текст раундов поиска без переносов — делится.
        assertEquals(
            listOf("• Метро MRT", "• Оплата: карта или наличные"),
            TransitTips.bullets("Сейчас поищу. • Метро MRT • Оплата: **карта** или наличные"),
        )
        assertEquals(listOf("• Автобус 1", "• Часы 6–24"), TransitTips.bullets("- Автобус 1\n- Часы 6–24"))
        assertEquals(emptyList<String>(), TransitTips.bullets("Не знаю."))
    }
}
