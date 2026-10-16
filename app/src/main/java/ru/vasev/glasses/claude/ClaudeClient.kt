package ru.vasev.glasses.claude

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
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vasev.glasses.BuildConfig
import java.time.Duration

class ClaudeException(message: String) : Exception(message)

/** Короткие голосовые ответы Claude через официальный Java SDK (Messages API). */
class ClaudeClient(private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY) {

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(60))
            .build()
    }

    /** Отправляет вопрос (и, если есть, JPEG-кадр с очков) и возвращает текст ответа. */
    suspend fun ask(question: String, jpeg: ByteArray? = null): String = withContext(Dispatchers.IO) {
        if (!hasApiKey) throw ClaudeException("Не задан API-ключ Anthropic (anthropic_api_key в local.properties).")

        val blocks = buildList {
            if (jpeg != null) {
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
            add(ContentBlockParam.ofText(TextBlockParam.builder().text(question).build()))
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(2048L)
            .system(SYSTEM_PROMPT)
            .addUserMessageOfBlockParams(blocks)
            // Голосовой ассистент: важнее скорость ответа, чем глубина размышлений.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // Если классификатор безопасности откажет ложно, сервер сам повторит запрос на запасной модели.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw ClaudeException("Неверный API-ключ Anthropic.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeException("API-ключ не имеет доступа к модели $MODEL.")
        } catch (e: RateLimitException) {
            throw ClaudeException("Слишком много запросов к Claude, подождите немного.")
        } catch (e: AnthropicServiceException) {
            throw ClaudeException("Ошибка Claude API (${e.statusCode()}).")
        } catch (e: AnthropicIoException) {
            throw ClaudeException("Нет связи с Claude. Проверьте интернет.")
        }

        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            return@withContext "Извините, на это я ответить не могу."
        }
        message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString(" ")
            .trim()
            .ifEmpty { throw ClaudeException("Claude вернул пустой ответ.") }
    }

    private companion object {
        const val MODEL = "claude-sonnet-5-5"

        val SYSTEM_PROMPT = """
            Ты — голосовой помощник в умных очках Ray-Ban Meta. Твой ответ будет зачитан вслух синтезатором речи.
            Отвечай по-русски, разговорно и по делу: одно–три коротких предложения.
            Не используй markdown, списки, эмодзи, ссылки и спецсимволы. Числа и единицы пиши так, как их произносят.
            Если к вопросу приложен снимок, это то, что сейчас видит пользователь через очки: опирайся на него.
            Если на снимке не видно того, о чём спрашивают, честно скажи об этом.
        """.trimIndent()
    }
}
