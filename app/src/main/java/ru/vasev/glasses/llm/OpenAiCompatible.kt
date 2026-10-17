package ru.vasev.glasses.llm

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
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

    const val PHOTO_PLACEHOLDER = "[фото]"

    fun buildBody(request: ChatRequest, dialect: Dialect): JSONObject {
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
            put("stream", false)
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
        val u = json.optJSONObject("usage") ?: JSONObject()
        val cached = when {
            u.has("prompt_cache_hit_tokens") -> u.optLong("prompt_cache_hit_tokens")
            else -> u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L
        }
        val usage = Usage(
            input = u.optLong("prompt_tokens"),
            output = u.optLong("completion_tokens"),
            cacheRead = cached,
            reasoning = u.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens") ?: 0L,
        )
        return text to usage
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
        DebugFaults.consume(id)?.let { throw it }
        val apiKey = try {
            keys.decrypt(id)
        } catch (e: ApiKeyException) {
            null
        } ?: throw ProviderException(id, ProviderException.Kind.NO_KEY)

        val body = OpenAiCompatibleFormat.buildBody(request, config.dialect).toString()
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
            .build()
        val client = request.timeoutMs?.let { http.newBuilder().callTimeout(it, TimeUnit.MILLISECONDS).build() } ?: http

        val started = SystemClock.elapsedRealtime()
        val response = try {
            client.newCall(httpRequest).await()
        } catch (e: IOException) {
            throw ProviderException(id, ProviderException.Kind.NETWORK, detail = e.javaClass.simpleName)
        }
        val latency = SystemClock.elapsedRealtime() - started

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                // В теле ошибки только описание от API, ключа там нет.
                val detail = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw ProviderException(id, OpenAiCompatibleFormat.kindFor(it.code), it.code, detail?.take(200))
            }
            val (answer, usage) = try {
                OpenAiCompatibleFormat.parse(text)
            } catch (e: Exception) {
                throw ProviderException(id, ProviderException.Kind.EMPTY, detail = "не разобран ответ")
            }
            if (answer != null && answer.isEmpty()) throw ProviderException(id, ProviderException.Kind.EMPTY)
            ChatResult(answer, request.model, usage, latency)
        }
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
