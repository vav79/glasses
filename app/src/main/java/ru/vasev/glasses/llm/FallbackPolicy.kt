package ru.vasev.glasses.llm

/** Что делать после сбоя основной модели. Чистая логика — покрыта unit-тестами. */
sealed interface FallbackDecision {
    /** Повторить через запасную модель; [stripPhotos] — снимки из истории заменить на «[photo]». */
    data class Retry(val stripPhotos: Boolean) : FallbackDecision

    /** Не повторять. Текст для пользователя по [reason] собирает [LlmService] на языке приложения. */
    data class Fail(val reason: FailReason) : FallbackDecision
}

enum class FailReason {
    /** Сети нет совсем — «Нет интернета». */
    NO_INTERNET,

    /** «Что я вижу» при сбое Claude, а фото в запасную модель отправлять нельзя. */
    PHOTO_NOT_SENT,

    /** Показать исходную ошибку провайдера. */
    ORIGINAL_ERROR,
}

object FallbackPolicy {

    /** Сбои, при которых Claude считается недоступным. 401 (ключ) и отказы модели сюда не входят. */
    private val TRIGGERS = setOf(
        ProviderException.Kind.FORBIDDEN,
        ProviderException.Kind.RATE_LIMIT,
        ProviderException.Kind.SERVER,
        ProviderException.Kind.NETWORK,
    )

    fun decide(
        error: ProviderException,
        online: Boolean,
        fallbackEnabled: Boolean,
        hasFallbackKey: Boolean,
        photoInCurrentTurn: Boolean,
        photosAllowed: Boolean,
    ): FallbackDecision {
        if (error.kind == ProviderException.Kind.NETWORK && !online) return FallbackDecision.Fail(FailReason.NO_INTERNET)
        val eligible = error.provider == ProviderId.ANTHROPIC && error.kind in TRIGGERS
        if (!eligible || !fallbackEnabled || !hasFallbackKey) return FallbackDecision.Fail(FailReason.ORIGINAL_ERROR)
        // Автоматическая подмена: без разрешения снимок с камеры не уходит в другую модель.
        if (photoInCurrentTurn && !photosAllowed) return FallbackDecision.Fail(FailReason.PHOTO_NOT_SENT)
        return FallbackDecision.Retry(stripPhotos = !photosAllowed)
    }
}
