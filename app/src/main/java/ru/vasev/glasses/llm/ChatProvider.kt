package ru.vasev.glasses.llm

import ru.vasev.glasses.R
import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.i18n.L

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

    /** Текст для пользователя на текущем языке приложения. */
    val userMessage: String
        get() {
            val p = provider.title
            val code = status?.let { " ($it)" } ?: ""
            return when (kind) {
                Kind.NO_KEY -> L.s(R.string.llm_no_key, p)
                Kind.AUTH -> L.s(R.string.llm_auth, p)
                Kind.NO_BALANCE -> L.s(R.string.llm_no_balance, p)
                Kind.FORBIDDEN -> L.s(R.string.llm_forbidden, p)
                Kind.RATE_LIMIT -> L.s(R.string.llm_rate_limit, p)
                Kind.SERVER -> L.s(R.string.llm_server, p, code)
                Kind.NETWORK -> L.s(R.string.llm_network, p)
                Kind.BAD_REQUEST -> L.s(R.string.llm_bad_request, p, code)
                Kind.EMPTY -> L.s(R.string.llm_empty, p)
            }
        }

    val needsKey: Boolean get() = kind == Kind.NO_KEY || kind == Kind.AUTH
}

interface ChatProvider {
    val id: ProviderId

    /** Бросает [ProviderException]. Ключ берётся из зашифрованного хранилища в момент запроса. */
    suspend fun complete(request: ChatRequest): ChatResult
}
