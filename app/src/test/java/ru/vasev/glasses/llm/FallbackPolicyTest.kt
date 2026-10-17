package ru.vasev.glasses.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.vasev.glasses.llm.ProviderException.Kind

class FallbackPolicyTest {

    private fun claude(kind: Kind, status: Int? = null) = ProviderException(ProviderId.ANTHROPIC, kind, status)

    private fun decide(
        error: ProviderException,
        online: Boolean = true,
        enabled: Boolean = true,
        hasKey: Boolean = true,
        photoNow: Boolean = false,
        photosAllowed: Boolean = false,
    ) = FallbackPolicy.decide(error, online, enabled, hasKey, photoNow, photosAllowed)

    @Test
    fun `403, 429, 5xx and network trigger fallback`() {
        for (error in listOf(claude(Kind.FORBIDDEN, 403), claude(Kind.RATE_LIMIT, 429), claude(Kind.SERVER, 529), claude(Kind.NETWORK))) {
            assertEquals(error.kind.name, FallbackDecision.Retry(stripPhotos = true), decide(error))
        }
    }

    @Test
    fun `no internet at all - no fallback, say so`() {
        assertEquals(FallbackDecision.Fail(FallbackPolicy.NO_INTERNET), decide(claude(Kind.NETWORK), online = false))
    }

    @Test
    fun `bad key and bad request do not trigger fallback`() {
        for (kind in listOf(Kind.AUTH, Kind.BAD_REQUEST, Kind.NO_KEY, Kind.EMPTY)) {
            val error = claude(kind)
            assertEquals(kind.name, FallbackDecision.Fail(error.userMessage), decide(error))
        }
    }

    @Test
    fun `only claude failures fall back`() {
        val error = ProviderException(ProviderId.DEEPSEEK, Kind.SERVER, 503)
        assertEquals(FallbackDecision.Fail(error.userMessage), decide(error))
    }

    @Test
    fun `disabled in settings or no deepseek key - original error`() {
        val error = claude(Kind.SERVER, 500)
        assertEquals(FallbackDecision.Fail(error.userMessage), decide(error, enabled = false))
        assertEquals(FallbackDecision.Fail(error.userMessage), decide(error, hasKey = false))
    }

    @Test
    fun `what do I see without photo permission - photo not sent`() {
        assertEquals(
            FallbackDecision.Fail(FallbackPolicy.PHOTO_NOT_SENT),
            decide(claude(Kind.SERVER, 529), photoNow = true, photosAllowed = false),
        )
    }

    @Test
    fun `photos allowed - retry keeps photos`() {
        assertEquals(FallbackDecision.Retry(stripPhotos = false), decide(claude(Kind.SERVER, 529), photoNow = true, photosAllowed = true))
    }
}
