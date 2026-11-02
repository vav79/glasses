package ru.vasev.glasses.claude

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Событие календаря от модели (инструмент add_calendar_event). Время — местное, как его назвал пользователь.
 * Конец не указан — час после начала. Чистая логика, тесты — CalendarEventTest.
 */
data class CalendarEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
) {
    fun startMillis(zone: ZoneId = ZoneId.systemDefault()): Long = start.atZone(zone).toInstant().toEpochMilli()
    fun endMillis(zone: ZoneId = ZoneId.systemDefault()): Long = end.atZone(zone).toInstant().toEpochMilli()

    companion object {
        private const val MAX_TITLE = 200

        fun parse(input: JSONObject): CalendarEvent? {
            val title = input.optString("title").trim().take(MAX_TITLE).ifEmpty { return null }
            val start = time(input.optString("start")) ?: return null
            val allDay = input.optBoolean("all_day", false)
            val endGiven = time(input.optString("end"))
            val startDay = start.toLocalDate().atStartOfDay()
            val end = if (allDay) {
                endGiven?.toLocalDate()?.atStartOfDay()?.takeIf { it.isAfter(startDay) } ?: startDay.plusDays(1)
            } else {
                endGiven?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
            }
            return CalendarEvent(
                title = title,
                start = if (allDay) startDay else start,
                end = end,
                allDay = allDay,
                location = input.optString("location").trim().ifEmpty { null },
                notes = input.optString("notes").trim().ifEmpty { null },
            )
        }

        /** «2026-11-05T14:30», «2026-11-05 14:30», «2026-11-05T14:30:00» и просто дата. */
        private fun time(text: String): LocalDateTime? {
            val t = text.trim().replace(' ', 'T')
            if (t.isEmpty()) return null
            return try {
                if (t.length == 10) java.time.LocalDate.parse(t).atStartOfDay() else LocalDateTime.parse(t)
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}
