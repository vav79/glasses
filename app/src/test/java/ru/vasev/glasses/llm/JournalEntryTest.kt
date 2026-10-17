package ru.vasev.glasses.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONObject
import org.junit.Test
import java.time.Instant

class JournalEntryTest {

    private val entry = JournalEntry(
        time = Instant.parse("2026-10-17T10:23:44.756Z"),
        role = Role.TRANSLATION,
        provider = ProviderId.ANTHROPIC,
        model = "claude-haiku-5-5",
        status = "ok",
        latencyMs = 1100,
        usage = Usage(input = 89, output = 12, cacheRead = 0, cacheWrite = 0, reasoning = 0),
        photo = "none",
        fallback = false,
        costUsd = 0.0000149,
    )

    @Test
    fun `round trip through jsonl line`() {
        val line = entry.toJson().toString()
        assertEquals(entry, JournalEntry.fromJson(JSONObject(line)))
    }

    @Test
    fun `metadata only by default - no text fields`() {
        val json = entry.toJson()
        assertFalse(json.has("question"))
        assertFalse(json.has("answer"))
        assertEquals("translation", json.getString("mode"))
    }

    @Test
    fun `texts are kept when present`() {
        val withTexts = entry.copy(question = "Merhaba", answer = "Здравствуйте")
        assertEquals(withTexts, JournalEntry.fromJson(JSONObject(withTexts.toJson().toString())))
    }
}
