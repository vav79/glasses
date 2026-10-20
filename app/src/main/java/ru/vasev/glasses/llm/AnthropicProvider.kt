package ru.vasev.glasses.llm

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    override suspend fun complete(request: ChatRequest): ChatResult = withoutUnsupportedCountry(request) { completeOnce(it) }

    /**
     * Веб-поиск отвечает 400 «Country code XX is not supported» на страну в `user_location` (так с TH):
     * повторяем один раз без страны, часовой пояс остаётся. Ошибка приходит до первого слова — повтор безопасен.
     */
    private suspend fun withoutUnsupportedCountry(request: ChatRequest, block: suspend (ChatRequest) -> ChatResult): ChatResult {
        val search = request.webSearch
        // Страну, которую поиск уже отверг, сразу не отправляем — без лишнего запроса с 400.
        val fitted = if (search?.country != null && search.country in unsupportedCountries) request.copy(webSearch = search.copy(country = null)) else request
        return try {
            block(fitted)
        } catch (e: ProviderException) {
            val ws = fitted.webSearch
            val country = ws?.country
            if (country == null || e.message?.contains(UNSUPPORTED_COUNTRY) != true) throw e
            unsupportedCountries += country
            Log.w("LLM", "web search: country $country not supported, retrying without it")
            block(fitted.copy(webSearch = ws.copy(country = null)))
        }
    }

    /** Страны, которые веб-поиск не принимает в `user_location` (до перезапуска приложения). */
    private val unsupportedCountries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun completeOnce(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
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

    /**
     * Потоковый ответ с циклом инструментов: модель вызвала инструмент приложения (`tool_use`) — выполняем,
     * возвращаем результат и продолжаем; сервер прервал длинный ход (`pause_turn`) — продолжаем как есть.
     * Ответ модели возвращается в следующий раунд целиком (вместе с thinking-блоками и результатами поиска) —
     * так требует API. Текст всех раундов идёт в [onText] одним потоком.
     */
    override suspend fun stream(
        request: ChatRequest,
        onText: (String) -> Unit,
        onUsage: (Usage) -> Unit,
        onEvent: () -> Unit,
    ): ChatResult = withoutUnsupportedCountry(request) { streamOnce(it, onText, onUsage, onEvent) }

    private suspend fun streamOnce(
        request: ChatRequest,
        onText: (String) -> Unit,
        onUsage: (Usage) -> Unit,
        onEvent: () -> Unit,
    ): ChatResult =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val text = StringBuilder()
            val history = mutableListOf<ToolRound>()
            val cited = LinkedHashMap<String, WebSource>()
            val found = LinkedHashMap<String, WebSource>()
            var total = Usage()
            var refused = false
            var rounds = 0
            while (true) {
                val params = params(request, history)
                val accumulator = MessageAccumulator.create()
                // Текст после поиска или инструмента — новая мысль: отделяем пробелом от написанного до него.
                var afterTool = false
                var round = Usage()
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
                                accumulator.accumulate(event)
                                onEvent()
                                event.messageStart().ifPresent { start ->
                                    val u = start.message().usage()
                                    round = round.copy(
                                        input = u.inputTokens(),
                                        cacheRead = u.cacheReadInputTokens().orElse(0L),
                                        cacheWrite = u.cacheCreationInputTokens().orElse(0L),
                                    )
                                    onUsage(total + round)
                                }
                                event.contentBlockStart().ifPresent { start ->
                                    val block = start.contentBlock()
                                    if (!block.isText() && !block.isThinking() && !block.isRedactedThinking()) afterTool = true
                                }
                                // Только текст: дельты размышлений (thinking) не озвучиваются и не показываются.
                                event.contentBlockDelta().ifPresent { delta ->
                                    delta.delta().text().ifPresent { piece ->
                                        var value = piece.text()
                                        if (afterTool && text.isNotEmpty() && !text.last().isWhitespace() && !value.startsWith(" ")) value = " $value"
                                        afterTool = false
                                        text.append(value)
                                        onText(value)
                                    }
                                }
                                event.messageDelta().ifPresent { delta ->
                                    round = round.copy(output = delta.usage().outputTokens())
                                    onUsage(total + round)
                                }
                            }
                        }
                    } finally {
                        closer.cancel()
                    }
                }
                val message = accumulator.message()
                val usage = message.usage()
                total += Usage(
                    input = usage.inputTokens(),
                    output = usage.outputTokens(),
                    cacheRead = usage.cacheReadInputTokens().orElse(0L),
                    cacheWrite = usage.cacheCreationInputTokens().orElse(0L),
                    webSearches = usage.serverToolUse().map { it.webSearchRequests() }.orElse(0L),
                )
                onUsage(total)
                collectSources(message, cited, found)

                val stop = message.stopReason().orElse(null)
                if (stop == StopReason.REFUSAL) {
                    refused = true
                    break
                }
                val calls = message.content().mapNotNull { it.toolUse().orElse(null) }
                val tools = request.tools
                val continues = (stop == StopReason.TOOL_USE && calls.isNotEmpty() && tools != null) || stop == StopReason.PAUSE_TURN
                if (!continues || ++rounds > MAX_TOOL_ROUNDS) break

                val results = calls.map { call ->
                    // Инструмент может идти несколько секунд (снимок, геопозиция, курс) — сторожевой таймер не должен сработать.
                    onEvent()
                    val output = tools!!.execute(ToolCall(call.id(), call.name(), inputOf(call)))
                    onEvent()
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(call.id()).content(output.content).isError(output.isError).build(),
                    )
                }
                history += ToolRound(message, results)
            }
            // Источники, на которые модель сослалась; если ссылок в тексте нет — первые найденные страницы.
            val sources = cited.values.ifEmpty { found.values.take(MAX_UNCITED_SOURCES) }.toList()
            val answer = if (refused) {
                null
            } else {
                text.toString().trim().ifEmpty { throw ProviderException(id, ProviderException.Kind.EMPTY) }
            }
            ChatResult(answer, request.model, total, SystemClock.elapsedRealtime() - started, sources)
        }

    /** Раунд цикла инструментов: ответ модели целиком и результаты инструментов (пусто для `pause_turn`). */
    private class ToolRound(val message: Message, val toolResults: List<ContentBlockParam>)

    private fun collectSources(message: Message, cited: MutableMap<String, WebSource>, found: MutableMap<String, WebSource>) {
        for (block in message.content()) {
            block.text().ifPresent { textBlock ->
                textBlock.citations().orElse(emptyList()).forEach { citation ->
                    citation.webSearchResultLocation().ifPresent { cited.putIfAbsent(it.url(), WebSource(it.url(), it.title().orElse(""))) }
                }
            }
            block.webSearchToolResult().ifPresent { result ->
                result.content().resultBlocks().ifPresent { list ->
                    list.forEach { found.putIfAbsent(it.url(), WebSource(it.url(), it.title())) }
                }
            }
        }
    }

    /** Аргументы вызова инструмента; неразборчивые — пустой объект (инструмент ответит ошибкой). */
    private fun inputOf(call: ToolUseBlock): JSONObject =
        runCatching { JSONObject(call._input().convert(Map::class.java)) }.getOrElse { JSONObject() }

    /** Параметры запроса с ключом из хранилища; [history] — раунды цикла инструментов этого ответа. */
    private suspend fun params(request: ChatRequest, history: List<ToolRound> = emptyList()): MessageCreateParams {
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
        for (round in history) {
            builder.addMessage(round.message)
            if (round.toolResults.isNotEmpty()) builder.addUserMessageOfBlockParams(round.toolResults)
        }
        request.tools?.specs?.forEach { builder.addTool(tool(it)) }
        request.webSearch?.let { search ->
            val location = UserLocation.builder()
            search.country?.let(location::country)
            search.timezone?.let(location::timezone)
            builder.addTool(
                WebSearchTool20250305.builder()
                    .maxUses(search.maxUses.toLong())
                    .apply { if (search.country != null || search.timezone != null) userLocation(location.build()) }
                    .build(),
            )
        }
        if (request.role == Role.ASSISTANT && request.cache) {
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
            // Текст ошибки API (без ключа) — чтобы 400 можно было понять по логу; неподдержанная страна поиска — особый случай.
            val message = e.message.orEmpty()
            if (kind == ProviderException.Kind.BAD_REQUEST) Log.w("LLM", "anthropic $status: ${message.take(LOG_ERROR_MAX)}")
            val unsupportedCountry = message.contains("Country code") && message.contains("not supported")
            throw ProviderException(id, kind, status, detail = if (unsupportedCountry) UNSUPPORTED_COUNTRY else null)
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

    private fun tool(spec: ToolSpec): Tool {
        val properties = Tool.InputSchema.Properties.builder()
        spec.properties.forEach { (name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)) }
        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(spec.required).build())
            .build()
    }

    private companion object {
        const val API_KEY_HEADER = "x-api-key"
        const val NO_KEY_PLACEHOLDER = "not-set"
        const val PHOTO_PLACEHOLDER = "[photo]"
        const val UNSUPPORTED_COUNTRY = "web search country not supported"
        const val LOG_ERROR_MAX = 400

        /** Сколько найденных страниц показать, если модель не сослалась ни на одну. */
        const val MAX_UNCITED_SOURCES = 3
    }
}
