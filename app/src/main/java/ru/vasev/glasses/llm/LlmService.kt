package ru.vasev.glasses.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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

/** Потоковый ответ; [entry] — запись журнала, которую дописывает [LlmService.finishTurn] в конце хода. */
class StreamedAnswer(val answer: Answer, internal val entry: JournalEntry)

/** Замер хода ассистента, который заполняет экран: конец фразы пользователя и первый звук ответа. */
class TurnMetrics(val endOfSpeechAt: Long) {
    @Volatile
    var firstSoundAt: Long? = null

    val firstSoundMs: Long? get() = firstSoundAt?.let { it - endOfSpeechAt }
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
        val (request, autoVision) = prepare(role, system, turns, maxTokens, effort)
        val primary = try {
            attempt(request, fallback = false)
        } catch (e: ProviderException) {
            val retry = fallbackFor(e, request)
            val result = try {
                attempt(retry, fallback = true, cause = e)
            } catch (e2: ProviderException) {
                throw LlmException(L.s(R.string.fallback_also_failed, e.userMessage))
            }
            return Answer(result.text, result.model, viaFallback = true, autoVision = false)
        }
        return Answer(primary.text, primary.model, viaFallback = false, autoVision = autoVision)
    }

    /**
     * Потоковый ответ: [onText] получает текст по мере генерации. Запасная модель — только если сбой случился
     * до первого слова (иначе ответ уже звучит, и повтор другой моделью с середины был бы странным);
     * [onFallback] вызывается перед её первым словом. Запись журнала об удачном ответе экран дописывает
     * в конце хода через [finishTurn] — со временем до первого звука и признаком «перебит».
     */
    suspend fun stream(
        role: Role,
        system: String,
        turns: List<Turn>,
        maxTokens: Long,
        effort: Effort,
        metrics: TurnMetrics,
        onFallback: () -> Unit,
        onText: (String) -> Unit,
    ): StreamedAnswer {
        val (request, autoVision) = prepare(role, system, turns, maxTokens, effort)
        var delivered = false
        val primary = try {
            attemptStream(request, fallback = false, cause = null, metrics) {
                delivered = true
                onText(it)
            }
        } catch (e: ProviderException) {
            if (delivered) throw LlmException(e.userMessage)
            val retry = fallbackFor(e, request)
            onFallback()
            val (result, entry) = try {
                attemptStream(retry, fallback = true, cause = e, metrics, onText)
            } catch (e2: ProviderException) {
                throw LlmException(L.s(R.string.fallback_also_failed, e.userMessage))
            }
            return StreamedAnswer(Answer(result.text, result.model, viaFallback = true, autoVision = false), entry)
        }
        val (result, entry) = primary
        return StreamedAnswer(Answer(result.text, result.model, viaFallback = false, autoVision = autoVision), entry)
    }

    /** Конец хода ассистента: запись журнала с временем до первого звука и признаком «перебит». */
    fun finishTurn(streamed: StreamedAnswer, metrics: TurnMetrics, interrupted: Boolean) {
        journal.add(streamed.entry.copy(firstSoundMs = metrics.firstSoundMs, interrupted = interrupted))
    }

    /** Нет сети — сразу ошибка; модель по роли; снимок на модели без фото — модель того же провайдера с фото. */
    private fun prepare(role: Role, system: String, turns: List<Turn>, maxTokens: Long, effort: Effort): Pair<ChatRequest, Boolean> {
        if (!isOnline()) throw LlmException(L.s(R.string.no_internet))
        val request = ChatRequest(role, settings.model(role).value, system, turns, maxTokens, effort)
        if (request.photoInCurrentTurn && !request.model.vision) {
            // Выбрана модель без фото (DeepSeek V4-Pro), а запрос со снимком — берём модель того же провайдера с фото.
            ModelCatalog.visionFor(request.model)?.let { return request.copy(model = it) to true }
        }
        return request to false
    }

    /** Запрос для запасной модели или [LlmException] для пользователя, если повтора не будет. */
    private fun fallbackFor(e: ProviderException, request: ChatRequest): ChatRequest {
        val decision = FallbackPolicy.decide(
            error = e,
            online = isOnline(),
            fallbackEnabled = settings.fallbackEnabled.value,
            hasFallbackKey = keys.has(ModelCatalog.FALLBACK.provider),
            photoInCurrentTurn = request.photoInCurrentTurn,
            photosAllowed = settings.fallbackPhotos.value,
        )
        return when (decision) {
            is FallbackDecision.Fail -> throw LlmException(
                when (decision.reason) {
                    FailReason.NO_INTERNET -> L.s(R.string.no_internet)
                    FailReason.PHOTO_NOT_SENT -> L.s(R.string.photo_not_sent)
                    FailReason.ORIGINAL_ERROR -> e.userMessage
                },
                needsKey = e.needsKey,
            )
            is FallbackDecision.Retry -> (if (decision.stripPhotos) request.withoutPhotos() else request)
                .copy(model = ModelCatalog.FALLBACK, timeoutMs = FALLBACK_TIMEOUT_MS)
        }
    }

    private suspend fun attempt(request: ChatRequest, fallback: Boolean, cause: ProviderException? = null): ChatResult {
        val provider = providers.getValue(request.model.provider)
        val started = SystemClock.elapsedRealtime()
        return try {
            provider.complete(request).also { r ->
                val latency = SystemClock.elapsedRealtime() - started
                logOk(request, fallback, cause, r, latency, firstTextMs = null)
                journal.add(entry(request, fallback, if (r.text == null) "refused" else "ok", latency, r.usage, r.text))
            }
        } catch (e: ProviderException) {
            logError(request, fallback, cause, e, SystemClock.elapsedRealtime() - started)
            journal.add(entry(request, fallback, errorStatus(e), SystemClock.elapsedRealtime() - started, Usage(), null))
            throw e
        }
    }

    private suspend fun attemptStream(
        request: ChatRequest,
        fallback: Boolean,
        cause: ProviderException?,
        metrics: TurnMetrics,
        onText: (String) -> Unit,
    ): Pair<ChatResult, JournalEntry> {
        val provider = providers.getValue(request.model.provider)
        val started = SystemClock.elapsedRealtime()
        var firstTextAt: Long? = null
        var usage = Usage()
        return try {
            val r = try {
                stallGuarded { touch ->
                    provider.stream(
                        request,
                        onText = {
                            touch()
                            if (firstTextAt == null) firstTextAt = SystemClock.elapsedRealtime()
                            onText(it)
                        },
                        onUsage = {
                            touch()
                            usage = it
                        },
                        onEvent = touch,
                    )
                }
            } catch (e: StreamStalled) {
                // Связь пропала посреди потока, а сокет ещё не понял этого — не ждём таймаута чтения в 60 с.
                throw ProviderException(provider.id, ProviderException.Kind.NETWORK, detail = "stream stalled")
            }
            val latency = SystemClock.elapsedRealtime() - started
            logOk(request, fallback, cause, r, latency, firstTextAt?.let { it - started })
            r to entry(request, fallback, if (r.text == null) "refused" else "ok", latency, r.usage, r.text)
        } catch (e: ProviderException) {
            logError(request, fallback, cause, e, SystemClock.elapsedRealtime() - started)
            journal.add(entry(request, fallback, errorStatus(e), SystemClock.elapsedRealtime() - started, usage, null))
            throw e
        } catch (e: CancellationException) {
            // Перебили, пока модель ещё писала: счёт токенов — сколько успело прийти.
            Log.d(TAG, "${prefix(request)} status=interrupted latency=${SystemClock.elapsedRealtime() - started}ms in=${usage.input} out=${usage.output}")
            journal.add(
                entry(request, fallback, "interrupted", SystemClock.elapsedRealtime() - started, usage, null)
                    .copy(firstSoundMs = metrics.firstSoundMs, interrupted = true),
            )
            throw e
        }
    }

    private class StreamStalled : Exception()

    /** Сторожевой таймер потока: [STREAM_STALL_MS] без единого события — [StreamStalled], поток закрывается. */
    private suspend fun <T> stallGuarded(block: suspend (touch: () -> Unit) -> T): T = coroutineScope {
        val last = AtomicLong(SystemClock.elapsedRealtime())
        val watchdog = launch {
            while (true) {
                delay(STALL_CHECK_MS)
                if (SystemClock.elapsedRealtime() - last.get() > STREAM_STALL_MS) throw StreamStalled()
            }
        }
        try {
            block { last.set(SystemClock.elapsedRealtime()) }
        } finally {
            watchdog.cancel()
        }
    }

    private fun prefix(request: ChatRequest) =
        "role=${request.role.name.lowercase()} provider=${request.model.provider.name.lowercase()} model=${request.model.id}"

    private fun fallbackNote(fallback: Boolean, cause: ProviderException?) =
        if (fallback) "yes(${cause?.kind}${cause?.status?.let { " $it" } ?: ""})" else "no"

    private fun errorStatus(e: ProviderException) = "error(${e.kind}${e.status?.let { " $it" } ?: ""})"

    private fun logOk(request: ChatRequest, fallback: Boolean, cause: ProviderException?, r: ChatResult, latency: Long, firstTextMs: Long?) {
        val u = r.usage
        Log.d(
            TAG,
            "${prefix(request)} status=${if (r.text == null) "refused" else "ok"} latency=${latency}ms " +
                (firstTextMs?.let { "first_text=${it}ms " } ?: "") +
                "in=${u.input} out=${u.output} cache_read=${u.cacheRead} cache_write=${u.cacheWrite} " +
                "reasoning=${u.reasoning} photo=${photoNote(request)} fallback=${fallbackNote(fallback, cause)}",
        )
    }

    private fun logError(request: ChatRequest, fallback: Boolean, cause: ProviderException?, e: ProviderException, latency: Long) {
        Log.w(
            TAG,
            "${prefix(request)} status=${errorStatus(e)} latency=${latency}ms photo=${photoNote(request)} " +
                "fallback=${fallbackNote(fallback, cause)}",
        )
    }

    private fun entry(request: ChatRequest, fallback: Boolean, status: String, latencyMs: Long, usage: Usage, answer: String?): JournalEntry {
        val now = Instant.now()
        val texts = journalTexts()
        return JournalEntry(
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
        const val STREAM_STALL_MS = 20_000L
        const val STALL_CHECK_MS = 1_000L
    }
}
