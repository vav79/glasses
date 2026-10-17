package ru.vasev.glasses.llm

import ru.vasev.glasses.claude.Turn

/** Запрос к модели: системный промпт и разговор, который заканчивается новой репликой пользователя. */
data class ChatRequest(
    val role: Role,
    val model: ModelSpec,
    val system: String,
    val turns: List<Turn>,
    val maxTokens: Long,
    val effort: Effort,
    /** Ограничение на весь запрос (для запасной модели); null — по умолчанию клиента. */
    val timeoutMs: Long? = null,
) {
    /** Есть ли снимок в новой реплике (сценарий «Что я вижу»). */
    val photoInCurrentTurn: Boolean get() = (turns.lastOrNull() as? Turn.User)?.jpeg != null

    val hasPhotos: Boolean get() = turns.any { it is Turn.User && it.jpeg != null }

    /** Все снимки — пометкой «[фото]»: для моделей без фото и когда фото отправлять нельзя. */
    fun withoutPhotos(): ChatRequest = copy(
        turns = turns.map { turn ->
            if (turn is Turn.User && turn.jpeg != null) turn.copy(jpeg = null, photoPlaceholder = true) else turn
        },
    )
}

data class Usage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
)

/** Ответ модели. [text] = null — модель отказалась отвечать. */
data class ChatResult(val text: String?, val model: ModelSpec, val usage: Usage, val latencyMs: Long)

/** Сбой запроса к провайдеру — классифицирован, чтобы решить про запасную модель. */
class ProviderException(
    val provider: ProviderId,
    val kind: Kind,
    val status: Int? = null,
    detail: String? = null,
) : Exception("${provider.title}: $kind${status?.let { " ($it)" } ?: ""}${detail?.let { " — $it" } ?: ""}") {

    enum class Kind { NO_KEY, AUTH, NO_BALANCE, FORBIDDEN, RATE_LIMIT, SERVER, NETWORK, BAD_REQUEST, EMPTY }

    /** Текст для пользователя. */
    val userMessage: String
        get() = when (kind) {
            Kind.NO_KEY -> "Нет API-ключа ${provider.title}. Откройте «Настройки» и введите ключ."
            Kind.AUTH -> "API-ключ ${provider.title} не подходит. Замените его в «Настройках»."
            Kind.NO_BALANCE -> "На счёте ${provider.title} закончились средства."
            Kind.FORBIDDEN -> "${provider.title}: нет доступа к модели."
            Kind.RATE_LIMIT -> "Слишком много запросов к ${provider.title}, подождите немного."
            Kind.SERVER -> "${provider.title} временно недоступен${status?.let { " ($it)" } ?: ""}."
            Kind.NETWORK -> "Нет связи с ${provider.title}."
            Kind.BAD_REQUEST -> "Ошибка запроса к ${provider.title}${status?.let { " ($it)" } ?: ""}."
            Kind.EMPTY -> "${provider.title} вернул пустой ответ."
        }

    val needsKey: Boolean get() = kind == Kind.NO_KEY || kind == Kind.AUTH
}

interface ChatProvider {
    val id: ProviderId

    /** Бросает [ProviderException]. Ключ берётся из зашифрованного хранилища в момент запроса. */
    suspend fun complete(request: ChatRequest): ChatResult
}
