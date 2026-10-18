package ru.vasev.glasses.llm

import android.os.SystemClock
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.security.ApiKeyException
import ru.vasev.glasses.security.ApiKeyStore
import java.io.IOException
import java.time.Duration

/**
 * Claude через официальный Java SDK. Клиент собран с заглушкой вместо ключа: настоящий ключ расшифровывается
 * перед каждым запросом и уходит заголовком `x-api-key` только в этот запрос.
 */
class AnthropicProvider(private val keys: ApiKeyStore) : ChatProvider {

    override val id = ProviderId.ANTHROPIC

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(NO_KEY_PLACEHOLDER)
            .timeout(Duration.ofSeconds(60))
            .build()
    }

    override suspend fun complete(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        val params = params(request)
        val started = SystemClock.elapsedRealtime()
        val message = mapErrors { client.messages().create(params) }
        val latency = SystemClock.elapsedRealtime() - started

        val usage = message.usage().let {
            Usage(
                input = it.inputTokens(),
                output = it.outputTokens(),
                cacheRead = it.cacheReadInputTokens().orElse(0L),
                cacheWrite = it.cacheCreationInputTokens().orElse(0L),
            )
        }
        val text = if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            null
        } else {
            message.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString(" ")
                .trim()
                .ifEmpty { throw ProviderException(id, ProviderException.Kind.EMPTY) }
        }
        ChatResult(text, request.model, usage, latency)
    }

    override suspend fun stream(
        request: ChatRequest,
        onText: (String) -> Unit,
        onUsage: (Usage) -> Unit,
        onEvent: () -> Unit,
    ): ChatResult =
        withContext(Dispatchers.IO) {
            val params = params(request)
            val started = SystemClock.elapsedRealtime()
            val text = StringBuilder()
            var usage = Usage()
            var refused = false
            coroutineScope {
                val response = mapErrors { client.messages().createStreaming(params) }
                // Чтение потока блокирующее: при отмене закрываем ответ из соседней корутины, и чтение обрывается.
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        response.close()
                    }
                }
                try {
                    mapErrors {
                        response.stream().forEach { event ->
                            onEvent()
                            event.messageStart().ifPresent { start ->
                                val u = start.message().usage()
                                usage = usage.copy(
                                    input = u.inputTokens(),
                                    cacheRead = u.cacheReadInputTokens().orElse(0L),
                                    cacheWrite = u.cacheCreationInputTokens().orElse(0L),
                                )
                                onUsage(usage)
                            }
                            // Только текст: дельты размышлений (thinking) не озвучиваются и не показываются.
                            event.contentBlockDelta().ifPresent { delta ->
                                delta.delta().text().ifPresent { piece ->
                                    text.append(piece.text())
                                    onText(piece.text())
                                }
                            }
                            event.messageDelta().ifPresent { delta ->
                                usage = usage.copy(output = delta.usage().outputTokens())
                                onUsage(usage)
                                if (delta.delta().stopReason().orElse(null) == StopReason.REFUSAL) refused = true
                            }
                        }
                    }
                } finally {
                    closer.cancel()
                }
            }
            val answer = if (refused) {
                null
            } else {
                text.toString().trim().ifEmpty { throw ProviderException(id, ProviderException.Kind.EMPTY) }
            }
            ChatResult(answer, request.model, usage, SystemClock.elapsedRealtime() - started)
        }

    /** Параметры запроса с ключом из хранилища. */
    private suspend fun params(request: ChatRequest): MessageCreateParams {
        DebugFaults.consume(id)?.let { throw it }
        val apiKey = try {
            keys.decrypt(id)
        } catch (e: ApiKeyException) {
            null
        } ?: throw ProviderException(id, ProviderException.Kind.NO_KEY)

        val builder = MessageCreateParams.builder()
            .model(request.model.id)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .putAdditionalHeader(API_KEY_HEADER, apiKey)
        if (request.effort == Effort.NONE) {
            // «Без размышлений»: у Sonnet 5.5 `disabled` даёт 400 — самый низкий уровень там `between_tools`,
            // у Haiku 5.5 — `disabled`. Оба принимаются только при effort не выше high (у нас low).
            val off = if (request.model == ModelCatalog.CLAUDE_SONNET) "between_tools" else "disabled"
            builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to off)))
        }
        for (turn in request.turns) {
            when (turn) {
                is Turn.User -> builder.addUserMessageOfBlockParams(userBlocks(turn, request.model.vision))
                is Turn.Assistant -> builder.addAssistantMessage(turn.text)
            }
        }
        if (request.role == Role.ASSISTANT) {
            // Автоматический кэш: точка кэширования сама сдвигается к концу растущей истории.
            builder.cacheControl(CacheControlEphemeral.builder().build())
        }
        if (request.model == ModelCatalog.CLAUDE_SONNET) {
            // Если классификатор безопасности откажет ложно, сервер сам повторит запрос на запасной модели Claude.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    /** Ошибки SDK → [ProviderException]; обрыв из-за отмены корутины — это отмена, а не сбой сети. */
    private suspend fun <T> mapErrors(block: () -> T): T {
        val context = currentCoroutineContext()
        return try {
            block()
        } catch (e: UnauthorizedException) {
            throw ProviderException(id, ProviderException.Kind.AUTH, 401)
        } catch (e: PermissionDeniedException) {
            throw ProviderException(id, ProviderException.Kind.FORBIDDEN, 403)
        } catch (e: RateLimitException) {
            throw ProviderException(id, ProviderException.Kind.RATE_LIMIT, 429)
        } catch (e: AnthropicServiceException) {
            val status = e.statusCode()
            val kind = if (status >= 500) ProviderException.Kind.SERVER else ProviderException.Kind.BAD_REQUEST
            throw ProviderException(id, kind, status)
        } catch (e: ProviderException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!context.isActive) throw CancellationException("stream closed on cancel")
            if (e is AnthropicIoException || e is IOException || e.cause is IOException) {
                throw ProviderException(id, ProviderException.Kind.NETWORK, detail = e.javaClass.simpleName)
            }
            throw e
        }
    }

    private fun userBlocks(turn: Turn.User, vision: Boolean): List<ContentBlockParam> = buildList {
        val jpeg = turn.jpeg
        if (jpeg != null && vision) {
            add(
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                                .build(),
                        )
                        .build(),
                ),
            )
        }
        if (turn.photoPlaceholder || (jpeg != null && !vision)) add(text(PHOTO_PLACEHOLDER))
        add(text(turn.text))
    }

    private fun text(value: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(value).build())

    private companion object {
        const val API_KEY_HEADER = "x-api-key"
        const val NO_KEY_PLACEHOLDER = "not-set"
        const val PHOTO_PLACEHOLDER = "[photo]"
    }
}
