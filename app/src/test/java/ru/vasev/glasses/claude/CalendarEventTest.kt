package ru.vasev.glasses.claude

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CalendarEventTest {

    @Test
    fun `событие с началом — конец через час`() {
        val e = CalendarEvent.parse(JSONObject().put("title", "Стоматолог").put("start", "2026-11-05T14:30"))!!
        assertEquals(LocalDateTime.of(2026, 11, 5, 14, 30), e.start)
        assertEquals(LocalDateTime.of(2026, 11, 5, 15, 30), e.end)
        assertNull(e.location)
    }

    @Test
    fun `конец раньше начала не принимается`() {
        val e = CalendarEvent.parse(JSONObject().put("title", "X").put("start", "2026-11-05 14:30").put("end", "2026-11-05T13:00"))!!
        assertEquals(LocalDateTime.of(2026, 11, 5, 15, 30), e.end)
    }

    @Test
    fun `событие на весь день`() {
        val e = CalendarEvent.parse(JSONObject().put("title", "Отпуск").put("start", "2026-12-01").put("all_day", true))!!
        assertTrue(e.allDay)
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0), e.start)
        assertEquals(LocalDateTime.of(2026, 12, 2, 0, 0), e.end)
    }

    @Test
    fun `без названия или с неверным временем — null`() {
        assertNull(CalendarEvent.parse(JSONObject().put("start", "2026-11-05T14:30")))
        assertNull(CalendarEvent.parse(JSONObject().put("title", "X").put("start", "завтра в три")))
    }
}
