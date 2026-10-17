package ru.vasev.glasses.translate

import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.llm.Answer
import ru.vasev.glasses.llm.Effort
import ru.vasev.glasses.llm.LlmService
import ru.vasev.glasses.llm.Role

/** Перевод одной реплики моделью, выбранной для «Перевода» (по умолчанию Claude Haiku 5.5). */
class Translator(private val llm: LlmService) {

    /** [Answer.text] = null — модель отказалась переводить. */
    suspend fun translate(userPrompt: String): Answer = llm.complete(
        role = Role.TRANSLATION,
        system = TranslationPrompt.SYSTEM,
        turns = listOf(Turn.User(userPrompt)),
        maxTokens = MAX_TOKENS,
        // Для перевода размышления только добавляют задержку: у DeepSeek выключаются, у Claude — минимальные.
        effort = Effort.NONE,
    )

    private companion object {
        const val MAX_TOKENS = 300L
    }
}
