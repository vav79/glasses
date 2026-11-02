package ru.vasev.glasses.llm

/** Состояние модели для чипа внизу меню. */
enum class ModelHealth { AVAILABLE, SLOW, UNAVAILABLE, NO_KEY }

/** Попытка запроса к провайдеру (из журнала): [status] — `ok`, `refused` или `error(KIND [code])`. */
data class Attempt(val at: Long, val status: String, val latencyMs: Long) {
    val ok: Boolean get() = !status.startsWith("error")
}

/**
 * Правила статуса модели — без отдельных платных проверок, по реальным запросам (журнал) и связи.
 * Чистая логика, тесты — ModelHealthTest.
 * - Нет ключа → [ModelHealth.NO_KEY]; нет сети → [ModelHealth.UNAVAILABLE].
 * - Последний запрос отклонён ключом (AUTH, FORBIDDEN, NO_BALANCE) или 3 последних за 10 минут — ошибки → недоступна.
 * - За 10 минут был лимит или перегрузка (RATE_LIMIT, 429, 529) или медиана длительности 5 последних удачных
 *   запросов за 30 минут больше [SLOW_MS] → задержки.
 */
object ModelHealthRules {

    const val SLOW_MS = 12_000L
    private const val RECENT_MS = 10 * 60 * 1000L
    private const val SPEED_WINDOW_MS = 30 * 60 * 1000L
    private val KEY_ERRORS = listOf("AUTH", "FORBIDDEN", "NO_BALANCE")
    private val BUSY_ERRORS = listOf("RATE_LIMIT", "429", "529")

    /** [attempts] — попытки этого провайдера, новые первыми. */
    fun status(hasKey: Boolean, online: Boolean, attempts: List<Attempt>, now: Long): ModelHealth {
        if (!hasKey) return ModelHealth.NO_KEY
        if (!online) return ModelHealth.UNAVAILABLE
        val last = attempts.firstOrNull()
        if (last != null && !last.ok && KEY_ERRORS.any { it in last.status }) return ModelHealth.UNAVAILABLE
        val recent = attempts.filter { now - it.at <= RECENT_MS }
        if (recent.size >= 3 && recent.take(3).none { it.ok }) return ModelHealth.UNAVAILABLE
        if (recent.any { a -> !a.ok && BUSY_ERRORS.any { it in a.status } }) return ModelHealth.SLOW
        val speeds = attempts.filter { it.ok && now - it.at <= SPEED_WINDOW_MS }.take(5).map { it.latencyMs }.sorted()
        if (speeds.isNotEmpty() && speeds[speeds.size / 2] > SLOW_MS) return ModelHealth.SLOW
        return ModelHealth.AVAILABLE
    }
}
