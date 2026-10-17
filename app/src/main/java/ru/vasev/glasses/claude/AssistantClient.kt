package ru.vasev.glasses.claude

import ru.vasev.glasses.i18n.AppLanguage
import ru.vasev.glasses.llm.Answer
import ru.vasev.glasses.llm.Effort
import ru.vasev.glasses.llm.LlmService
import ru.vasev.glasses.llm.Role

/** Короткие голосовые ответы ассистента моделью, выбранной для «Ассистента» (по умолчанию Claude Sonnet 5.5). */
class AssistantClient(private val llm: LlmService) {

    /**
     * Отправляет разговор ([turns] заканчивается новым вопросом пользователя); отвечает на [language] — языке приложения.
     * В историю ответы попадают только текстом — без thinking-блоков, поэтому обрезка истории, замена старых
     * снимков на «[photo]» и смена провайдера посреди разговора не ломают проверку целостности истории у Claude.
     */
    suspend fun ask(turns: List<Turn>, language: AppLanguage): Answer = llm.complete(
        role = Role.ASSISTANT,
        system = systemPrompt(language),
        turns = turns,
        maxTokens = MAX_TOKENS,
        // Голосовой ассистент: важнее скорость ответа, чем глубина размышлений.
        effort = Effort.LOW,
    )

    companion object {
        private const val MAX_TOKENS = 2048L

        /** Промпт на английском; язык ответа — язык приложения. Смена языка меняет промпт (кэш начнётся заново). */
        fun systemPrompt(language: AppLanguage): String = """
            You are a voice assistant built into Ray-Ban Meta smart glasses. Your reply will be read aloud by a speech synthesizer.
            Always answer in ${language.englishName}, conversationally and to the point: one to three short sentences.
            Do not use markdown, lists, emoji, links or special characters. Write numbers and units the way they are spoken.
            If a photo is attached to the question, it shows what the user is seeing through the glasses right now: rely on it.
            If the photo does not show what is being asked about, say so honestly.
            Take earlier turns of the conversation into account: a follow-up question may refer to a photo taken earlier.
            The [photo] marker stands for a photo that was part of the conversation but is no longer attached.
        """.trimIndent()
    }
}
