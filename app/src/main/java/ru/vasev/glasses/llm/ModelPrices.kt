package ru.vasev.glasses.llm

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * Примерная стоимость запроса в долларах — для журнала, не для бухгалтерии.
 * Цены за 1M токенов: Claude — официальный прайс Anthropic; DeepSeek — цены в часы пик,
 * вне пика — со скидкой [DEEPSEEK_OFF_PEAK_FACTOR].
 */
object ModelPrices {

    data class Price(
        val input: Double,
        val output: Double,
        val cacheRead: Double,
        val cacheWrite: Double = input,
        /** У OpenAI-совместимых API `prompt_tokens` уже включает попадания в кэш. */
        val inputIncludesCache: Boolean = false,
        val offPeakDiscount: Boolean = false,
    )

    private val prices = mapOf(
        "claude-sonnet-5-5" to Price(input = 2.0, output = 10.0, cacheRead = 0.20, cacheWrite = 2.50),
        "claude-haiku-5-5" to Price(input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.125),
        "deepseek-flash" to Price(input = 0.30, output = 1.20, cacheRead = 0.03, inputIncludesCache = true, offPeakDiscount = true),
        "deepseek-v4-pro" to Price(input = 1.20, output = 4.80, cacheRead = 0.12, inputIncludesCache = true, offPeakDiscount = true),
    )

    /** Веб-поиск Claude: $10 за 1000 поисков (сверх токенов результатов, они уже во входных); неудачные не оплачиваются. */
    const val WEB_SEARCH_USD = 0.01

    /** Скидка DeepSeek вне часов пика (оценка). */
    const val DEEPSEEK_OFF_PEAK_FACTOR = 0.5

    /** null — модели нет в таблице цен. Токены размышлений входят в `output` у обоих провайдеров. */
    fun estimate(modelId: String, usage: Usage, at: Instant): Double? {
        val p = prices[modelId] ?: return null
        val uncachedInput = if (p.inputIncludesCache) (usage.input - usage.cacheRead).coerceAtLeast(0) else usage.input
        val tokens = (uncachedInput * p.input + usage.output * p.output + usage.cacheRead * p.cacheRead +
            usage.cacheWrite * p.cacheWrite) / 1_000_000.0
        val usd = if (p.offPeakDiscount && !isDeepSeekPeak(at)) tokens * DEEPSEEK_OFF_PEAK_FACTOR else tokens
        return usd + usage.webSearches * WEB_SEARCH_USD
    }

    /** Часы пика DeepSeek: будни, 01:00–04:00 и 06:00–10:00 UTC. */
    fun isDeepSeekPeak(at: Instant): Boolean {
        val utc = at.atZone(ZoneOffset.UTC)
        if (utc.dayOfWeek == DayOfWeek.SATURDAY || utc.dayOfWeek == DayOfWeek.SUNDAY) return false
        return utc.hour in 1..3 || utc.hour in 6..9
    }
}
