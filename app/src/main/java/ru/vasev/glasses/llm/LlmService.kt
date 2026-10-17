package ru.vasev.glasses.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import ru.vasev.glasses.R
import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.security.ApiKeyStore
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Ошибка для пользователя. [needsKey] — проблема с ключом: показать путь в «Настройки». */
class LlmException(message: String, val needsKey: Boolean = false) : Exception(message)

/** Метка модели у ответа; подпись («· запасная», «· для фото») собирает экран на текущем языке. */
data class ModelTag(val title: String, val viaFallback: Boolean, val autoVision: Boolean)

/** Ответ модели. [text] = null — модель отказалась отвечать. */
data class Answer(val text: String?, val model: ModelSpec, val viaFallback: Boolean, val autoVision: Boolean) {
    val tag: ModelTag get() = ModelTag(model.title, viaFallback, autoVision)
}

/**
 * Единая точка запросов к ИИ: модель по роли из [AiSettings], автоматическая модель с фото для «Что я вижу»,
 * запасная модель при сбое Claude ([FallbackPolicy]) и строка лога `LLM` на каждую попытку.
 */
class LlmService(
    context: Context,
    private val keys: ApiKeyStore,
    private val settings: AiSettings,
    private val journal: RequestJournal,
    /** Сохранять ли в журнале тексты вопроса и ответа (настройка, по умолчанию выключена). */
    private val journalTexts: () -> Boolean,
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val providers: Map<ProviderId, ChatProvider> = listOf(
        AnthropicProvider(keys),
        OpenAiCompatibleProvider(ProviderConfigs.DEEPSEEK, keys, http),
        OpenAiCompatibleProvider(ProviderConfigs.OPENAI, keys, http),
    ).associateBy { it.id }

    /** Есть ли ключ для модели, выбранной для роли (без расшифровки). */
    fun ready(role: Role): Flow<Boolean> = combine(keys.saved, settings.model(role)) { saved, model -> model.provider in saved }

    fun model(role: Role) = settings.model(role)

    suspend fun complete(role: Role, system: String, turns: List<Turn>, maxTokens: Long, effort: Effort): Answer {
        if (!isOnline()) throw LlmException(L.s(R.string.no_internet))

        var request = ChatRequest(role, settings.model(role).value, system, turns, maxTokens, effort)
        var autoVision = false
        if (request.photoInCurrentTurn && !request.model.vision) {
            // Выбрана модель без фото (DeepSeek V4-Pro), а запрос со снимком — берём модель того же провайдера с фото.
            ModelCatalog.visionFor(request.model)?.let {
                request = request.copy(model = it)
                autoVision = true
            }
        }

        val primary = try {
            attempt(request, fallback = false)
        } catch (e: ProviderException) {
            val decision = FallbackPolicy.decide(
                error = e,
                online = isOnline(),
                fallbackEnabled = settings.fallbackEnabled.value,
                hasFallbackKey = keys.has(ModelCatalog.FALLBACK.provider),
                photoInCurrentTurn = request.photoInCurrentTurn,
                photosAllowed = settings.fallbackPhotos.value,
            )
            when (decision) {
                is FallbackDecision.Fail -> throw LlmException(
                    when (decision.reason) {
                        FailReason.NO_INTERNET -> L.s(R.string.no_internet)
                        FailReason.PHOTO_NOT_SENT -> L.s(R.string.photo_not_sent)
                        FailReason.ORIGINAL_ERROR -> e.userMessage
                    },
                    needsKey = e.needsKey,
                )
                is FallbackDecision.Retry -> {
                    val retry = (if (decision.stripPhotos) request.withoutPhotos() else request)
                        .copy(model = ModelCatalog.FALLBACK, timeoutMs = FALLBACK_TIMEOUT_MS)
                    val result = try {
                        attempt(retry, fallback = true, cause = e)
                    } catch (e2: ProviderException) {
                        throw LlmException(L.s(R.string.fallback_also_failed, e.userMessage))
                    }
                    return Answer(result.text, result.model, viaFallback = true, autoVision = false)
                }
            }
        }
        return Answer(primary.text, primary.model, viaFallback = false, autoVision = autoVision)
    }

    private suspend fun attempt(request: ChatRequest, fallback: Boolean, cause: ProviderException? = null): ChatResult {
        val provider = providers.getValue(request.model.provider)
        val started = SystemClock.elapsedRealtime()
        val prefix = "role=${request.role.name.lowercase()} provider=${provider.id.name.lowercase()} model=${request.model.id}"
        val fallbackNote = if (fallback) "yes(${cause?.kind}${cause?.status?.let { " $it" } ?: ""})" else "no"
        return try {
            provider.complete(request).also { r ->
                val u = r.usage
                record(request, fallback, if (r.text == null) "refused" else "ok", SystemClock.elapsedRealtime() - started, u, r.text)
                Log.d(
                    TAG,
                    "$prefix status=${if (r.text == null) "refused" else "ok"} latency=${SystemClock.elapsedRealtime() - started}ms " +
                        "in=${u.input} out=${u.output} cache_read=${u.cacheRead} cache_write=${u.cacheWrite} " +
                        "reasoning=${u.reasoning} photo=${photoNote(request)} fallback=$fallbackNote",
                )
            }
        } catch (e: ProviderException) {
            Log.w(
                TAG,
                "$prefix status=error(${e.kind}${e.status?.let { " $it" } ?: ""}) " +
                    "latency=${SystemClock.elapsedRealtime() - started}ms photo=${photoNote(request)} fallback=$fallbackNote",
            )
            record(request, fallback, "error(${e.kind}${e.status?.let { " $it" } ?: ""})", SystemClock.elapsedRealtime() - started, Usage(), null)
            throw e
        }
    }

    private fun record(request: ChatRequest, fallback: Boolean, status: String, latencyMs: Long, usage: Usage, answer: String?) {
        val now = Instant.now()
        val texts = journalTexts()
        journal.add(
            JournalEntry(
                time = now,
                role = request.role,
                provider = request.model.provider,
                model = request.model.id,
                status = status,
                latencyMs = latencyMs,
                usage = usage,
                photo = photoNote(request),
                fallback = fallback,
                costUsd = ModelPrices.estimate(request.model.id, usage, now),
                question = if (texts) (request.turns.lastOrNull() as? Turn.User)?.text else null,
                answer = if (texts) answer else null,
            ),
        )
    }

    private fun photoNote(request: ChatRequest): String = when {
        request.hasPhotos && request.model.vision -> "sent"
        request.hasPhotos || request.turns.any { it is Turn.User && it.photoPlaceholder } -> "placeholder"
        else -> "none"
    }

    private fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private companion object {
        const val TAG = "LLM"
        const val FALLBACK_TIMEOUT_MS = 20_000L
    }
}
