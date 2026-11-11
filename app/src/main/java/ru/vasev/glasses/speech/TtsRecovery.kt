package ru.vasev.glasses.speech

/**
 * Почему движок синтеза считаем отвалившимся. Процесс приложения со службой жестов живёт днями, а движок
 * (Google TTS — в том же процессе, что и распознаватель) система убивает и перезапускает сама: старый
 * TextToSpeech после этого «not bound to TTS engine» навсегда — ответ приходил в чат без голоса (баг 05.11).
 */
enum class TtsFailure(val log: String) {
    /** Движок не поднялся (init вернул ERROR). */
    INIT("init failed"),

    /** setLanguage не сработал, а движок не привязан или язык раньше работал. */
    LANGUAGE_LOST("language lost"),

    /** speak()/synthesizeToFile() вернули ERROR — фраза не принята, onDone не придёт. */
    CALL_ERROR("speak returned ERROR"),

    /** onError от движка. */
    ON_ERROR("onError"),

    /** Нет onStart за [TtsRecovery.START_TIMEOUT_MS]. */
    NO_START("no onStart"),
}

/** Итог одной попытки сказать фразу. */
sealed interface TtsAttempt {
    data object Spoken : TtsAttempt

    /** Голоса для языка действительно нет (движок живой) — повтор не поможет. */
    data object NoVoice : TtsAttempt

    data class Failed(val reason: TtsFailure) : TtsAttempt
}

object TtsRecovery {
    /** Сколько ждать начала звука после принятой фразы. */
    const val START_TIMEOUT_MS = 5_000L

    // Коды TextToSpeech.setLanguage (константы, чтобы правила проверялись на JVM без Android).
    const val LANG_MISSING_DATA = -1
    const val LANG_NOT_SUPPORTED = -2

    /**
     * Разбор ответа setLanguage. null — язык выставлен. [bound] — движок отвечает (voices не null): у отвязанного
     * TextToSpeech любой вызов возвращает ошибку, и «нет голоса» было бы ложью. [workedBefore] — этим языком уже говорили.
     */
    fun language(result: Int, bound: Boolean, workedBefore: Boolean): TtsAttempt? = when {
        result != LANG_MISSING_DATA && result != LANG_NOT_SUPPORTED -> null
        !bound || workedBefore -> TtsAttempt.Failed(TtsFailure.LANGUAGE_LOST)
        else -> TtsAttempt.NoVoice
    }

    /**
     * Фраза с одним повтором: попытка не удалась из-за движка — [recreate] (новый TextToSpeech) и ещё раз.
     * Вторая неудача — итог как есть (вызывающий скажет об ошибке, а не будет ждать onDone вечно).
     */
    suspend fun speak(attempt: suspend () -> TtsAttempt, recreate: suspend (TtsFailure) -> Unit): TtsAttempt {
        val first = attempt()
        if (first !is TtsAttempt.Failed) return first
        recreate(first.reason)
        return attempt()
    }
}
