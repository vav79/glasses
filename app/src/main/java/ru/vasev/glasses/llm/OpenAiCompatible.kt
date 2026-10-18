package ru.vasev.glasses.llm

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.security.ApiKeyException
import ru.vasev.glasses.security.ApiKeyStore
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Сборка запроса и разбор ответа Chat Completions — чистые функции, покрыты unit-тестами. */
object OpenAiCompatibleFormat {

    const val PHOTO_PLACEHOLDER = "[photo]"

    /** Кусок потокового ответа (одна строка SSE `data: {...}`). */
    data class StreamChunk(val text: String?, val finishReason: String?, val usage: Usage?)

    /**
     * Строка SSE → кусок ответа; null — служебная строка, пустая строка или `[DONE]`.
     * `reasoning_content` (размышления DeepSeek) пропускается: его не озвучиваем и не показываем.
     */
    fun parseStreamLine(line: String): StreamChunk? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return null
        val json = JSONObject(data)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        val text = delta?.takeUnless { it.isNull("content") }?.optString("content")?.takeIf { it.isNotEmpty() }
        val finish = choice?.takeUnless { it.isNull("finish_reason") }?.optString("finish_reason")
        val usage = json.optJSONObject("usage")?.let(::usageOf)
        return StreamChunk(text, finish, usage)
    }

    fun buildBody(request: ChatRequest, dialect: Dialect, stream: Boolean = false): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", request.system))
        for (turn in request.turns) {
            when (turn) {
                is Turn.User -> messages.put(JSONObject().put("role", "user").put("content", userContent(turn, request.model.vision)))
                is Turn.Assistant -> messages.put(JSONObject().put("role", "assistant").put("content", turn.text))
            }
        }
        return JSONObject().apply {
            put("model", request.model.id)
            put("messages", messages)
            put("stream", stream)
            // Счёт токенов приходит последним куском потока.
            if (stream) put("stream_options", JSONObject().put("include_usage", true))
            when (dialect) {
                Dialect.DEEPSEEK -> {
                    put("max_tokens", request.maxTokens)
                    // Thinking у DeepSeek включён по умолчанию с effort=high; "none" выключает его совсем.
                    put("reasoning_effort", if (request.effort == Effort.NONE) "none" else "low")
                }
                Dialect.OPENAI -> {
                    put("max_completion_tokens", request.maxTokens)
                    put("reasoning_effort", if (request.effort == Effort.NONE) "minimal" else "low")
                }
            }
        }
    }

    /** Текст — строкой; с фото — массивом частей: картинка data-URL (detail=high, чтобы читался мелкий текст) и текст. */
    private fun userContent(turn: Turn.User, vision: Boolean): Any {
        val jpeg = turn.jpeg
        val placeholder = turn.photoPlaceholder || (jpeg != null && !vision)
        if (jpeg == null || !vision) return if (placeholder) "$PHOTO_PLACEHOLDER\n${turn.text}" else turn.text
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        return JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl).put("detail", "high")))
            .put(JSONObject().put("type", "text").put("text", turn.text))
    }

    /** Разбор ответа. Текст null — ответ остановлен фильтром контента. */
    fun parse(body: String): Pair<String?, Usage> {
        val json = JSONObject(body)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        val content = if (message.isNull("content")) "" else message.optString("content").trim()
        val text = if (choice.optString("finish_reason") == "content_filter") null else content
        return text to usageOf(json.optJSONObject("usage") ?: JSONObject())
    }

    private fun usageOf(u: JSONObject): Usage {
        val cached = when {
            u.has("prompt_cache_hit_tokens") -> u.optLong("prompt_cache_hit_tokens")
            else -> u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L
        }
        return Usage(
            input = u.optLong("prompt_tokens"),
            output = u.optLong("completion_tokens"),
            cacheRead = cached,
            reasoning = u.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens") ?: 0L,
        )
    }

    fun kindFor(status: Int): ProviderException.Kind = when (status) {
        401 -> ProviderException.Kind.AUTH
        402 -> ProviderException.Kind.NO_BALANCE
        403 -> ProviderException.Kind.FORBIDDEN
        429 -> ProviderException.Kind.RATE_LIMIT
        in 500..599 -> ProviderException.Kind.SERVER
        else -> ProviderException.Kind.BAD_REQUEST
    }
}

/**
 * Клиент OpenAI-совместимого Chat Completions API (DeepSeek, позже OpenAI) — настраивается [ProviderConfig].
 * Ключ расшифровывается перед каждым запросом и уходит только в заголовок `Authorization` этого запроса.
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val keys: ApiKeyStore,
    private val http: OkHttpClient,
) : ChatProvider {

    override val id: ProviderId get() = config.provider

    override suspend fun complete(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        val httpRequest = httpRequest(request, stream = false)
        val client = request.timeoutMs?.let { http.newBuilder().callTimeout(it, TimeUnit.MILLISECONDS).build() } ?: http

        val started = SystemClock.elapsedRealtime()
        val response = try {
            client.newCall(httpRequest).await()
        } catch (e: IOException) {
            throw ProviderException(id, ProviderException.Kind.NETWORK, detail = e.javaClass.simpleName)
        }
        val latency = SystemClock.elapsedRealtime() - started

        response.use {
            if (!it.isSuccessful) throw httpError(it)
            val text = it.body?.string().orEmpty()
            val (answer, usage) = try {
                OpenAiCompatibleFormat.parse(text)
            } catch (e: Exception) {
                throw ProviderException(id, ProviderException.Kind.EMPTY, detail = "unparsable response")
            }
            if (answer != null && answer.isEmpty()) throw ProviderException(id, ProviderException.Kind.EMPTY)
            ChatResult(answer, request.model, usage, latency)
        }
    }

    override suspend fun stream(
        request: ChatRequest,
        onText: (String) -> Unit,
        onUsage: (Usage) -> Unit,
        onEvent: () -> Unit,
    ): ChatResult =
        withContext(Dispatchers.IO) {
            val httpRequest = httpRequest(request, stream = true)
            // Для потока ограничиваем паузу между кусками, а не весь ответ: подробности могут идти долго.
            val client = request.timeoutMs?.let { http.newBuilder().readTimeout(it, TimeUnit.MILLISECONDS).build() } ?: http
            val started = SystemClock.elapsedRealtime()
            val response = try {
                client.newCall(httpRequest).await()
            } catch (e: IOException) {
                throw ProviderException(id, ProviderException.Kind.NETWORK, detail = e.javaClass.simpleName)
            }
            val text = StringBuilder()
            var usage = Usage()
            var filtered = false
            response.use {
                if (!it.isSuccessful) throw httpError(it)
                coroutineScope {
                    val source = it.body!!.source()
                    // Чтение блокирующее: при отмене закрываем ответ, и чтение обрывается.
                    val closer = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            it.close()
                        }
                    }
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            onEvent()
                            val chunk = try {
                                OpenAiCompatibleFormat.parseStreamLine(line)
                            } catch (e: Exception) {
                                throw ProviderException(id, ProviderException.Kind.EMPTY, detail = "unparsable stream")
                            } ?: continue
                            chunk.text?.let { piece ->
                                text.append(piece)
                                onText(piece)
                            }
                            if (chunk.finishReason == "content_filter") filtered = true
                            chunk.usage?.let { u ->
                                usage = u
                                onUsage(u)
                            }
                        }
                    } catch (e: IOException) {
                        if (!isActive) throw CancellationException("stream closed on cancel")
                        throw ProviderException(id, ProviderException.Kind.NETWORK, detail = e.javaClass.simpleName)
                    } finally {
                        closer.cancel()
                    }
                }
            }
            val answer = if (filtered) {
                null
            } else {
                text.toString().trim().ifEmpty { throw ProviderException(id, ProviderException.Kind.EMPTY) }
            }
            ChatResult(answer, request.model, usage, SystemClock.elapsedRealtime() - started)
        }

    private suspend fun httpRequest(request: ChatRequest, stream: Boolean): Request {
        DebugFaults.consume(id)?.let { throw it }
        val apiKey = try {
            keys.decrypt(id)
        } catch (e: ApiKeyException) {
            null
        } ?: throw ProviderException(id, ProviderException.Kind.NO_KEY)
        val body = OpenAiCompatibleFormat.buildBody(request, config.dialect, stream).toString()
        return Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
            .build()
    }

    /** В теле ошибки только описание от API, ключа там нет. */
    private fun httpError(response: Response): ProviderException {
        val text = response.body?.string().orEmpty()
        val detail = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
        return ProviderException(id, OpenAiCompatibleFormat.kindFor(response.code), response.code, detail?.take(200))
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) { _, _, _ -> response.close() } else response.close()
            }
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
