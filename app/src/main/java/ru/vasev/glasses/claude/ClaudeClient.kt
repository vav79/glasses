package ru.vasev.glasses.claude

import android.util.Base64
import android.util.Log
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.claude.Anthropic.text

/** Короткие голосовые ответы Claude через официальный Java SDK (Messages API). */
class ClaudeClient {

    /**
     * Отправляет разговор ([turns] заканчивается новым вопросом пользователя) и возвращает текст ответа.
     * В историю ответы Claude попадают только текстом — без thinking-блоков, поэтому обрезка истории
     * и замена старых снимков на «[фото]» не ломают проверку целостности истории на стороне API.
     */
    suspend fun ask(turns: List<Turn>): String {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(2048L)
            .system(SYSTEM_PROMPT)
        for (turn in turns) {
            when (turn) {
                is Turn.User -> builder.addUserMessageOfBlockParams(userBlocks(turn))
                is Turn.Assistant -> builder.addAssistantMessage(turn.text)
            }
        }

        val params = builder
            // Автоматический кэш: точка кэширования сама сдвигается к концу растущей истории.
            .cacheControl(CacheControlEphemeral.builder().build())
            // Голосовой ассистент: важнее скорость ответа, чем глубина размышлений.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // Если классификатор безопасности откажет ложно, сервер сам повторит запрос на запасной модели.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val message = Anthropic.create(params)

        val usage = message.usage()
        Log.d(
            TAG,
            "Claude usage: input=${usage.inputTokens()} cache_read=${usage.cacheReadInputTokens().orElse(0L)} " +
                "cache_write=${usage.cacheCreationInputTokens().orElse(0L)} output=${usage.outputTokens()}",
        )

        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            return "Извините, на это я ответить не могу."
        }
        return message.text()
            .ifEmpty { throw ClaudeException("Claude вернул пустой ответ.") }
    }

    private fun userBlocks(turn: Turn.User): List<ContentBlockParam> = buildList {
        turn.jpeg?.let { jpeg ->
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
        if (turn.photoPlaceholder) add(text(PHOTO_PLACEHOLDER))
        add(text(turn.text))
    }

    private fun text(value: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(value).build())

    private companion object {
        const val PHOTO_PLACEHOLDER = "[фото]"
        const val MODEL = "claude-sonnet-5-5"

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
