package ru.vasev.glasses.claude

import ru.vasev.glasses.answer.SpokenAnswer
import ru.vasev.glasses.i18n.AppLanguage
import ru.vasev.glasses.llm.Effort
import ru.vasev.glasses.llm.LlmService
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.llm.StreamedAnswer
import ru.vasev.glasses.llm.TurnMetrics

/** Голосовые ответы ассистента моделью, выбранной для «Ассистента» (по умолчанию Claude Sonnet 5.5). */
class AssistantClient(private val llm: LlmService) {

    /**
     * Отправляет разговор ([turns] заканчивается новым вопросом пользователя) и получает ответ потоком;
     * отвечает на [language] — языке приложения. Ответ двухуровневый: суть, маркер [SpokenAnswer.MARKER],
     * подробности. В историю ответы попадают только текстом — без thinking-блоков, поэтому обрезка истории,
     * замена старых снимков на «[photo]» и смена провайдера посреди разговора не ломают проверку истории у Claude.
     */
    suspend fun stream(
        turns: List<Turn>,
        language: AppLanguage,
        metrics: TurnMetrics,
        onFallback: () -> Unit,
        onText: (String) -> Unit,
    ): StreamedAnswer = llm.stream(
        role = Role.ASSISTANT,
        system = systemPrompt(language),
        turns = turns,
        maxTokens = MAX_TOKENS,
        // Голосовой ассистент: без размышлений — первое слово приходит на ~1 с раньше (замер в CLAUDE.md).
        effort = Effort.NONE,
        metrics = metrics,
        onFallback = onFallback,
        onText = onText,
    )

    companion object {
        private const val MAX_TOKENS = 2048L

        /** Промпт на английском; язык ответа — язык приложения. Смена языка меняет промпт (кэш начнётся заново). */
        fun systemPrompt(language: AppLanguage): String = """
            You are a voice assistant built into Ray-Ban Meta smart glasses. Always answer in ${language.englishName}.
            Structure every answer in two parts:
            1. The essence in one or two short sentences. It is read aloud by a speech synthesizer as soon as it is written, so put the direct answer first, conversationally, with numbers and units written the way they are spoken.
            2. Only if there is something genuinely useful to add: a line containing just ${SpokenAnswer.MARKER}, then the details. The details are shown on the phone screen, not read aloud: keep them compact and write numbers with digits.
            Never use markdown, lists, emoji, links or special characters in either part, and never mention the marker or the two parts.
            If a photo is attached to the question, it shows what the user is seeing through the glasses right now: rely on it.
            If the photo does not show what is being asked about, say so honestly.
            Take earlier turns of the conversation into account: a follow-up question may refer to a photo taken earlier.
            The [photo] marker stands for a photo that was part of the conversation but is no longer attached.
        """.trimIndent()
    }
}
