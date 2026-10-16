package ru.vasev.glasses.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vasev.glasses.BuildConfig
import java.time.Duration

class ClaudeException(message: String) : Exception(message)

/** Общий клиент Anthropic для ассистента и переводчика: один ключ, одно пулом соединений. */
object Anthropic {

    private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(60))
            .build()
    }

    /** Выполняет запрос к Messages API, переводя ошибки SDK в понятные пользователю сообщения. */
    suspend fun create(params: MessageCreateParams): Message = withContext(Dispatchers.IO) {
        if (!hasApiKey) throw ClaudeException("Не задан API-ключ Anthropic (anthropic_api_key в local.properties).")
        try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw ClaudeException("Неверный API-ключ Anthropic.")
        } catch (e: PermissionDeniedException) {
            throw ClaudeException("API-ключ не имеет доступа к модели ${params.model()}.")
        } catch (e: RateLimitException) {
            throw ClaudeException("Слишком много запросов к Claude, подождите немного.")
        } catch (e: AnthropicServiceException) {
            throw ClaudeException("Ошибка Claude API (${e.statusCode()}).")
        } catch (e: AnthropicIoException) {
            throw ClaudeException("Нет связи с Claude. Проверьте интернет.")
        }
    }

    /** Текст ответа без thinking-блоков. */
    fun Message.text(): String = content()
        .mapNotNull { block -> block.text().orElse(null)?.text() }
        .joinToString(" ")
        .trim()
}
