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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ru.vasev.glasses.security.ApiKeyException
import ru.vasev.glasses.security.ApiKeyStore
import java.time.Duration

/** Ошибка Claude с текстом для пользователя. [needsKey] — проблема с API-ключом: показать путь в «Настройки». */
class ClaudeException(message: String, val needsKey: Boolean = false) : Exception(message)

/**
 * Общий клиент Anthropic для ассистента и переводчика: один пул соединений.
 * Сам клиент не знает ключа — ключ расшифровывается из [ApiKeyStore] в памяти перед каждым запросом
 * и уходит заголовком `x-api-key` только в этот запрос.
 */
object Anthropic {

    private lateinit var keys: ApiKeyStore

    fun init(store: ApiKeyStore) {
        keys = store
    }

    /** Сохранён ли ключ (без расшифровки). */
    val hasApiKey: StateFlow<Boolean> get() = keys.hasKey

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            // Заглушка вместо ключа: настоящий подставляется в каждый запрос, клиент его не хранит.
            .apiKey(NO_KEY_PLACEHOLDER)
            .timeout(Duration.ofSeconds(60))
            .build()
    }

    /** Выполняет запрос к Messages API, переводя ошибки SDK в понятные пользователю сообщения. */
    suspend fun create(params: MessageCreateParams): Message = withContext(Dispatchers.IO) {
        val apiKey = try {
            keys.decrypt()
        } catch (e: ApiKeyException) {
            throw ClaudeException(e.message ?: NO_KEY_MESSAGE, needsKey = true)
        } ?: throw ClaudeException(NO_KEY_MESSAGE, needsKey = true)

        try {
            client.messages().create(params.toBuilder().putAdditionalHeader(API_KEY_HEADER, apiKey).build())
        } catch (e: UnauthorizedException) {
            throw ClaudeException("API-ключ не подходит. Замените его в «Настройках».", needsKey = true)
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

    const val NO_KEY_MESSAGE = "Нет API-ключа Anthropic. Откройте вкладку «Настройки» и введите ключ."
    private const val API_KEY_HEADER = "x-api-key"
    private const val NO_KEY_PLACEHOLDER = "not-set"
}
