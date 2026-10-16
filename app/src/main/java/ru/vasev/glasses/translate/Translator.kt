package ru.vasev.glasses.translate

import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import ru.vasev.glasses.claude.Anthropic
import ru.vasev.glasses.claude.Anthropic.text
import ru.vasev.glasses.claude.ClaudeException

/** Перевод одной реплики через Claude Haiku 5.5. */
class Translator {

    /** Возвращает перевод или null, если модель отказалась переводить. */
    suspend fun translate(userPrompt: String): String? {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(TranslationPrompt.SYSTEM)
            .addUserMessage(userPrompt)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        val message = Anthropic.create(params)
        // У Haiku 5.5 нет серверного fallback на другую модель: отказ показываем как «перевод недоступен».
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) return null
        return message.text().ifEmpty { throw ClaudeException("Переводчик вернул пустой ответ.") }
    }

    private companion object {
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 300L
    }
}
