package ru.vasev.glasses.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelHealthTest {

    private val now = 10_000_000L
    private fun ok(agoMs: Long, latency: Long = 3_000) = Attempt(now - agoMs, "ok", latency)
    private fun err(agoMs: Long, status: String) = Attempt(now - agoMs, status, 1_000)

    @Test
    fun `нет ключа и нет сети`() {
        assertEquals(ModelHealth.NO_KEY, ModelHealthRules.status(false, true, emptyList(), now))
        assertEquals(ModelHealth.UNAVAILABLE, ModelHealthRules.status(true, false, emptyList(), now))
    }

    @Test
    fun `без запросов и после удачного — доступна`() {
        assertEquals(ModelHealth.AVAILABLE, ModelHealthRules.status(true, true, emptyList(), now))
        assertEquals(ModelHealth.AVAILABLE, ModelHealthRules.status(true, true, listOf(ok(1_000)), now))
    }

    @Test
    fun `ключ отклонён — недоступна`() {
        assertEquals(ModelHealth.UNAVAILABLE, ModelHealthRules.status(true, true, listOf(err(5_000, "error(AUTH 401)"), ok(60_000)), now))
    }

    @Test
    fun `три ошибки подряд за 10 минут — недоступна, давние — нет`() {
        val three = listOf(err(1_000, "error(NETWORK)"), err(2_000, "error(SERVER 500)"), err(3_000, "error(SERVER 503)"))
        assertEquals(ModelHealth.UNAVAILABLE, ModelHealthRules.status(true, true, three, now))
        val old = three.map { it.copy(at = it.at - 20 * 60 * 1000L) }
        assertEquals(ModelHealth.AVAILABLE, ModelHealthRules.status(true, true, old, now))
    }

    @Test
    fun `лимит или медленные ответы — задержки`() {
        assertEquals(ModelHealth.SLOW, ModelHealthRules.status(true, true, listOf(ok(1_000), err(60_000, "error(RATE_LIMIT 429)")), now))
        val slow = listOf(ok(1_000, 15_000), ok(2_000, 14_000), ok(3_000, 3_000))
        assertEquals(ModelHealth.SLOW, ModelHealthRules.status(true, true, slow, now))
    }
}
