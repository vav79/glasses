package ru.vasev.glasses.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.security.ApiKeyStore

class ModelCatalogTest {

    @Test
    fun `defaults stay as before`() {
        assertEquals("claude-sonnet-5-5", ModelCatalog.default(Role.ASSISTANT).id)
        assertEquals("claude-haiku-5-5", ModelCatalog.default(Role.TRANSLATION).id)
        assertEquals("deepseek-flash", ModelCatalog.FALLBACK.id)
        assertTrue(ModelCatalog.FALLBACK.vision)
    }

    @Test
    fun `vision substitute for text-only model is from the same provider`() {
        assertEquals(ModelCatalog.DEEPSEEK_FLASH, ModelCatalog.visionFor(ModelCatalog.DEEPSEEK_PRO))
        assertEquals(ModelCatalog.CLAUDE_SONNET, ModelCatalog.visionFor(ModelCatalog.CLAUDE_SONNET))
    }

    @Test
    fun `unknown saved id falls back to default`() {
        assertNull(ModelCatalog.byId("deepseek-chat"))
        assertEquals(ModelCatalog.DEEPSEEK_PRO, ModelCatalog.byId("deepseek-v4-pro"))
    }

    @Test
    fun `withoutPhotos turns every photo into placeholder`() {
        val request = ChatRequest(
            Role.ASSISTANT, ModelCatalog.CLAUDE_SONNET, "s",
            listOf(Turn.User("old", byteArrayOf(1)), Turn.Assistant("a"), Turn.User("new", byteArrayOf(2))),
            maxTokens = 10, effort = Effort.LOW,
        )
        assertTrue(request.photoInCurrentTurn)
        val stripped = request.withoutPhotos()
        assertFalse(stripped.hasPhotos)
        assertTrue(stripped.turns.filterIsInstance<Turn.User>().all { it.photoPlaceholder && it.jpeg == null })
    }

    @Test
    fun `anthropic key slot keeps legacy names - saved key still decrypts`() {
        assertEquals("anthropic_key_ct", ApiKeyStore.prefName(ProviderId.ANTHROPIC))
        assertArrayEquals("ru.vasev.glasses/anthropic_api_key".toByteArray(), ApiKeyStore.aad(ProviderId.ANTHROPIC))
        assertEquals("deepseek_key_ct", ApiKeyStore.prefName(ProviderId.DEEPSEEK))
    }
}
