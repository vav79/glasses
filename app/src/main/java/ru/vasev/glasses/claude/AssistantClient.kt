package ru.vasev.glasses.claude

import ru.vasev.glasses.llm.Answer
import ru.vasev.glasses.llm.Effort
import ru.vasev.glasses.llm.LlmService
import ru.vasev.glasses.llm.Role

/** Короткие голосовые ответы ассистента моделью, выбранной для «Ассистента» (по умолчанию Claude Sonnet 5.5). */
class AssistantClient(private val llm: LlmService) {

    /**
     * Отправляет разговор ([turns] заканчивается новым вопросом пользователя).
     * В историю ответы попадают только текстом — без thinking-блоков, поэтому обрезка истории, замена старых
     * снимков на «[фото]» и смена провайдера посреди разговора не ломают проверку целостности истории у Claude.
     */
    suspend fun ask(turns: List<Turn>): Answer = llm.complete(
        role = Role.ASSISTANT,
        system = SYSTEM_PROMPT,
        turns = turns,
        maxTokens = MAX_TOKENS,
        // Голосовой ассистент: важнее скорость ответа, чем глубина размышлений.
        effort = Effort.LOW,
    )

    private companion object {
        const val MAX_TOKENS = 2048L

        val SYSTEM_PROMPT = """
            Ты — голосовой помощник в умных очках Ray-Ban Meta. Твой ответ будет зачитан вслух синтезатором речи.
            Отвечай по-русски, разговорно и по делу: одно–три коротких предложения.
            Не используй markdown, списки, эмодзи, ссылки и спецсимволы. Числа и единицы пиши так, как их произносят.
            Если к вопросу приложен снимок, это то, что сейчас видит пользователь через очки: опирайся на него.
            Если на снимке не видно того, о чём спрашивают, честно скажи об этом.
            Учитывай предыдущие реплики разговора: уточняющий вопрос может относиться к снимку, сделанному раньше.
            Пометка [фото] означает снимок, который был в разговоре, но больше не приложен.
        """.trimIndent()
    }
}
