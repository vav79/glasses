package ru.vasev.glasses.speech

/**
 * Ошибка распознавания с текстом для пользователя.
 * [noSpeech] — никто ничего не сказал (таймаут или «нет совпадений»): в непрерывном режиме это не ошибка.
 * [transient] — временный сбой службы распознавания: можно сразу повторить.
 */
class SpeechException(
    message: String,
    val noSpeech: Boolean = false,
    val transient: Boolean = false,
) : Exception(message)

/** Распознанная фраза; [endOfSpeechAt] — момент конца речи (SystemClock.elapsedRealtime) для замера задержки. */
data class Heard(val text: String, val endOfSpeechAt: Long)

/** Кто говорит: [USER] — владелец (гарнитура/очки, если подключены), [PARTNER] — собеседник (микрофон телефона). */
enum class Talker { USER, PARTNER }

/**
 * Источник распознанной речи. Сейчас — SpeechRecognizer с микрофона телефона или Bluetooth-гарнитуры.
 * Позже сюда встанет звук с очков: DAT Speech или PCM из in-stream audio.
 */
interface SpeechSource {
    /** Слушает одну фразу на языке [languageTag] (BCP-47, например "tr-TR"), сказанную [talker]. */
    suspend fun listen(
        languageTag: String,
        talker: Talker = Talker.USER,
        onPartial: (String) -> Unit = {},
        /** Громкость голоса 0..1 — для пульсации кнопки микрофона. */
        onLevel: (Float) -> Unit = {},
    ): Heard
}

/** Перед фразой выбирает микрофон через [router], после — отключает микрофон гарнитуры. */
class RoutedSpeechSource(private val inner: SpeechSource, private val router: AudioRouter) : SpeechSource {
    override suspend fun listen(languageTag: String, talker: Talker, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Heard {
        router.routeMic(talker)
        return try {
            inner.listen(languageTag, talker, onPartial, onLevel)
        } finally {
            router.releaseMic()
        }
    }
}
