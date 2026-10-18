package ru.vasev.glasses.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelPricesTest {

    // 2026-10-14 — среда, 2026-10-17 — суббота.
    private val wednesdayPeak = Instant.parse("2026-10-14T07:30:00Z")
    private val wednesdayNight = Instant.parse("2026-10-14T12:00:00Z")
    private val saturdayPeakHour = Instant.parse("2026-10-17T07:30:00Z")

    @Test
    fun `claude cost counts input, output and cache separately`() {
        val usage = Usage(input = 1_000_000, output = 100_000, cacheRead = 1_000_000, cacheWrite = 0)
        // 1M × $2 + 0,1M × $10 + 1M × $0,20
        assertEquals(3.2, ModelPrices.estimate("claude-sonnet-5-5", usage, wednesdayPeak)!!, 1e-9)
        assertEquals(0.1 + 0.05, ModelPrices.estimate("claude-haiku-5-5", Usage(input = 1_000_000, output = 100_000), wednesdayPeak)!!, 1e-9)
    }

    @Test
    fun `deepseek input already includes cache hits`() {
        val usage = Usage(input = 1_000_000, output = 0, cacheRead = 400_000)
        // 0,6M × $0,30 + 0,4M × $0,03
        assertEquals(0.18 + 0.012, ModelPrices.estimate("deepseek-flash", usage, wednesdayPeak)!!, 1e-9)
    }

    @Test
    fun `deepseek off-peak is discounted`() {
        val usage = Usage(input = 1_000_000, output = 1_000_000)
        val peak = ModelPrices.estimate("deepseek-flash", usage, wednesdayPeak)!!
        assertEquals(peak * ModelPrices.DEEPSEEK_OFF_PEAK_FACTOR, ModelPrices.estimate("deepseek-flash", usage, wednesdayNight)!!, 1e-9)
        // Скидка вне пика — только у DeepSeek.
        val claude = Usage(input = 1_000_000)
        assertEquals(
            ModelPrices.estimate("claude-sonnet-5-5", claude, wednesdayPeak),
            ModelPrices.estimate("claude-sonnet-5-5", claude, wednesdayNight),
        )
    }

    @Test
    fun `peak hours are weekday 01-04 and 06-10 utc`() {
        assertTrue(ModelPrices.isDeepSeekPeak(Instant.parse("2026-10-14T01:00:00Z")))
        assertTrue(ModelPrices.isDeepSeekPeak(Instant.parse("2026-10-14T03:59:00Z")))
        assertFalse(ModelPrices.isDeepSeekPeak(Instant.parse("2026-10-14T04:00:00Z")))
        assertTrue(ModelPrices.isDeepSeekPeak(Instant.parse("2026-10-14T06:00:00Z")))
        assertFalse(ModelPrices.isDeepSeekPeak(Instant.parse("2026-10-14T10:00:00Z")))
        assertFalse(ModelPrices.isDeepSeekPeak(saturdayPeakHour))
    }

    @Test
    fun `unknown model has no estimate`() {
        assertNull(ModelPrices.estimate("gpt-x", Usage(input = 10), wednesdayPeak))
    }

    @Test
    fun `web search adds ten dollars per thousand searches`() {
        val tokens = ModelPrices.estimate("claude-sonnet-5-5", Usage(input = 10_000, output = 200), wednesdayPeak)!!
        val withSearch = ModelPrices.estimate("claude-sonnet-5-5", Usage(input = 10_000, output = 200, webSearches = 2), wednesdayPeak)!!
        assertEquals(0.02, withSearch - tokens, 1e-9)
    }

    @Test
    fun `usage of tool rounds adds up`() {
        val total = Usage(input = 100, output = 10, cacheRead = 50, webSearches = 1) + Usage(input = 300, output = 20, cacheWrite = 7, webSearches = 2)
        assertEquals(Usage(input = 400, output = 30, cacheRead = 50, cacheWrite = 7, webSearches = 3), total)
    }
}
