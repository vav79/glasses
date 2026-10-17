package ru.vasev.glasses.llm

/** Что делать после сбоя основной модели. Чистая логика — покрыта unit-тестами. */
sealed interface FallbackDecision {
    /** Повторить через запасную модель; [stripPhotos] — снимки из истории заменить на «[фото]». */
    data class Retry(val stripPhotos: Boolean) : FallbackDecision

    /** Не повторять, показать [message]. */
    data class Fail(val message: String) : FallbackDecision
}

object FallbackPolicy {

    /** Сбои, при которых Claude считается недоступным. 401 (ключ) и отказы модели сюда не входят. */
    private val TRIGGERS = setOf(
        ProviderException.Kind.FORBIDDEN,
        ProviderException.Kind.RATE_LIMIT,
        ProviderException.Kind.SERVER,
        ProviderException.Kind.NETWORK,
    )

    const val NO_INTERNET = "Нет интернета."
    const val PHOTO_NOT_SENT = "Claude недоступен, фото не отправлено."

    fun decide(
        error: ProviderException,
        online: Boolean,
        fallbackEnabled: Boolean,
        hasFallbackKey: Boolean,
        photoInCurrentTurn: Boolean,
        photosAllowed: Boolean,
    ): FallbackDecision {
        if (error.kind == ProviderException.Kind.NETWORK && !online) return FallbackDecision.Fail(NO_INTERNET)
        val eligible = error.provider == ProviderId.ANTHROPIC && error.kind in TRIGGERS
        if (!eligible || !fallbackEnabled || !hasFallbackKey) return FallbackDecision.Fail(error.userMessage)
        // Автоматическая подмена: без разрешения снимок с камеры не уходит в другую модель.
        if (photoInCurrentTurn && !photosAllowed) return FallbackDecision.Fail(PHOTO_NOT_SENT)
        return FallbackDecision.Retry(stripPhotos = !photosAllowed)
    }
}
