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

/**
 * Источник распознанной речи. Сейчас — микрофон телефона через SpeechRecognizer.
 * Позже сюда встанет звук с очков: DAT Speech или PCM из in-stream audio.
 */
interface SpeechSource {
    /** Слушает одну фразу на языке [languageTag] (BCP-47, например "tr-TR"). */
    suspend fun listen(languageTag: String, onPartial: (String) -> Unit = {}): Heard
}
