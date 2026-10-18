package ru.vasev.glasses.llm

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vasev.glasses.claude.Turn
import java.util.Base64

class OpenAiCompatibleFormatTest {

    private val jpeg = byteArrayOf(1, 2, 3)

    private fun request(
        model: ModelSpec,
        turns: List<Turn>,
        role: Role = Role.ASSISTANT,
        effort: Effort = Effort.LOW,
    ) = ChatRequest(role, model, "SYS", turns, maxTokens = 2048, effort = effort)

    @Test
    fun `system first, then history in order`() {
        val body = OpenAiCompatibleFormat.buildBody(
            request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("q1"), Turn.Assistant("a1"), Turn.User("q2"))),
            Dialect.DEEPSEEK,
        )
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        assertEquals("a1", messages.getJSONObject(2).getString("content"))
        assertEquals("deepseek-flash", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
    }

    @Test
    fun `photo goes as data url with high detail for vision model`() {
        val body = OpenAiCompatibleFormat.buildBody(
            request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("Что на вывеске?", jpeg))),
            Dialect.DEEPSEEK,
        )
        val parts = body.getJSONArray("messages").getJSONObject(1).get("content") as JSONArray
        val image = parts.getJSONObject(0).getJSONObject("image_url")
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg), image.getString("url"))
        assertEquals("high", image.getString("detail"))
        assertEquals("Что на вывеске?", parts.getJSONObject(1).getString("text"))
    }

    @Test
    fun `text-only model gets placeholder instead of photo`() {
        val body = OpenAiCompatibleFormat.buildBody(
            request(ModelCatalog.DEEPSEEK_PRO, listOf(Turn.User("Что это?", jpeg), Turn.Assistant("Кофе"), Turn.User("Сколько?"))),
            Dialect.DEEPSEEK,
        )
        val first = body.getJSONArray("messages").getJSONObject(1).get("content")
        assertEquals("[photo]\nЧто это?", first)
        assertFalse(body.toString().contains("image_url"))
    }

    @Test
    fun `stored placeholder is kept as text`() {
        val body = OpenAiCompatibleFormat.buildBody(
            request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("старый вопрос", photoPlaceholder = true), Turn.Assistant("a"), Turn.User("новый"))),
            Dialect.DEEPSEEK,
        )
        assertEquals("[photo]\nстарый вопрос", body.getJSONArray("messages").getJSONObject(1).get("content"))
    }

    @Test
    fun `deepseek dialect - max_tokens and reasoning_effort by role`() {
        val assistant = OpenAiCompatibleFormat.buildBody(request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("q"))), Dialect.DEEPSEEK)
        assertEquals(2048, assistant.getLong("max_tokens"))
        assertEquals("low", assistant.getString("reasoning_effort"))

        val translation = OpenAiCompatibleFormat.buildBody(
            request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("q")), Role.TRANSLATION, Effort.NONE),
            Dialect.DEEPSEEK,
        )
        assertEquals("none", translation.getString("reasoning_effort"))
        assertFalse(translation.has("max_completion_tokens"))
    }

    @Test
    fun `openai dialect uses max_completion_tokens`() {
        val body = OpenAiCompatibleFormat.buildBody(request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("q")), effort = Effort.NONE), Dialect.OPENAI)
        assertEquals(2048, body.getLong("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        assertEquals("minimal", body.getString("reasoning_effort"))
    }

    @Test
    fun `parse deepseek response with usage and cache`() {
        val (text, usage) = OpenAiCompatibleFormat.parse(
            """
            {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":" Доброе утро! ","reasoning_content":"..."}}],
             "usage":{"prompt_tokens":120,"completion_tokens":7,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":56,
                      "completion_tokens_details":{"reasoning_tokens":3},"total_tokens":127}}
            """.trimIndent(),
        )
        assertEquals("Доброе утро!", text)
        assertEquals(Usage(input = 120, output = 7, cacheRead = 64, reasoning = 3), usage)
    }

    @Test
    fun `parse openai-style cached tokens`() {
        val (_, usage) = OpenAiCompatibleFormat.parse(
            """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],
                "usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":4}}}""",
        )
        assertEquals(4, usage.cacheRead)
    }

    @Test
    fun `content filter is a refusal`() {
        val (text, _) = OpenAiCompatibleFormat.parse(
            """{"choices":[{"finish_reason":"content_filter","message":{"content":null}}],"usage":{}}""",
        )
        assertNull(text)
    }

    @Test
    fun `http status to error kind`() {
        assertEquals(ProviderException.Kind.AUTH, OpenAiCompatibleFormat.kindFor(401))
        assertEquals(ProviderException.Kind.NO_BALANCE, OpenAiCompatibleFormat.kindFor(402))
        assertEquals(ProviderException.Kind.RATE_LIMIT, OpenAiCompatibleFormat.kindFor(429))
        assertEquals(ProviderException.Kind.SERVER, OpenAiCompatibleFormat.kindFor(503))
        assertEquals(ProviderException.Kind.BAD_REQUEST, OpenAiCompatibleFormat.kindFor(422))
        assertTrue(ProviderException(ProviderId.DEEPSEEK, ProviderException.Kind.AUTH).needsKey)
    }

    @Test
    fun `stream body asks for usage in the last chunk`() {
        val body = OpenAiCompatibleFormat.buildBody(request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("q"))), Dialect.DEEPSEEK, stream = true)
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        val plain = OpenAiCompatibleFormat.buildBody(request(ModelCatalog.DEEPSEEK_FLASH, listOf(Turn.User("q"))), Dialect.DEEPSEEK)
        assertFalse(plain.getBoolean("stream"))
        assertFalse(plain.has("stream_options"))
    }

    @Test
    fun `sse lines - text, reasoning skipped, filter, usage, done`() {
        val text = OpenAiCompatibleFormat.parseStreamLine("""data: {"choices":[{"index":0,"delta":{"content":"Привет"},"finish_reason":null}]}""")
        assertEquals("Привет", text?.text)
        assertNull(text?.finishReason)

        // Размышления DeepSeek не озвучиваются и не показываются.
        val reasoning = OpenAiCompatibleFormat.parseStreamLine(
            """data: {"choices":[{"index":0,"delta":{"content":null,"reasoning_content":"думаю"},"finish_reason":null}]}""",
        )
        assertNull(reasoning?.text)

        val filtered = OpenAiCompatibleFormat.parseStreamLine("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}""")
        assertEquals("content_filter", filtered?.finishReason)

        val usage = OpenAiCompatibleFormat.parseStreamLine(
            """data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":40,"prompt_cache_hit_tokens":64,""" +
                """"completion_tokens_details":{"reasoning_tokens":10}}}""",
        )
        assertEquals(Usage(input = 120, output = 40, cacheRead = 64, reasoning = 10), usage?.usage)

        assertNull(OpenAiCompatibleFormat.parseStreamLine("data: [DONE]"))
        assertNull(OpenAiCompatibleFormat.parseStreamLine(": keep-alive"))
        assertNull(OpenAiCompatibleFormat.parseStreamLine(""))
    }
}
