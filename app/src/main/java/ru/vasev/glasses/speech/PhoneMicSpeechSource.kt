package ru.vasev.glasses.speech

import android.content.Context
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Распознавание одной фразы с микрофона телефона через системный SpeechRecognizer. Вызывать с главного потока.
 *
 * Распознаватель один на всё время работы: если создавать и уничтожать его на каждую фразу, отключение старого
 * экземпляра от службы Google прилетает в новый как ERROR_SERVER_DISCONNECTED и обрывает непрерывный режим.
 */
class PhoneMicSpeechSource(private val context: Context) : SpeechSource {

    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(languageTag: String, talker: Talker, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Heard {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw SpeechException(L.s(R.string.speech_no_service))
        }
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        return suspendCancellableCoroutine { cont ->
            var endOfSpeechAt = 0L
            cont.invokeOnCancellation { recognizer.cancel() }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (!cont.isActive) return
                    if (text.isEmpty()) {
                        cont.resumeWithException(SpeechException(L.s(R.string.speech_not_heard), noSpeech = true))
                    } else {
                        val end = if (endOfSpeechAt != 0L) endOfSpeechAt else SystemClock.elapsedRealtime()
                        cont.resume(Heard(text, end))
                    }
                }

                override fun onPartialResults(partialResults: Bundle) {
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onPartial)
                }

                override fun onEndOfSpeech() {
                    endOfSpeechAt = SystemClock.elapsedRealtime()
                }

                override fun onError(error: Int) {
                    if (cont.isActive) cont.resumeWithException(errorFor(error))
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                // RMS от распознавателя Google — примерно от -2 (тишина) до 10 дБ (громко).
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        }
    }

    private fun errorFor(error: Int): SpeechException = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            SpeechException(L.s(R.string.speech_not_heard), noSpeech = true)
        // Сбои связи со службой распознавания — повторяемые.
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
            SpeechException(L.s(R.string.speech_interrupted), transient = true)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechException(L.s(R.string.speech_no_mic_permission))
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            SpeechException(L.s(R.string.speech_no_network))
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechException(L.s(R.string.speech_language_unavailable))
        else -> SpeechException(L.s(R.string.speech_error_code, error))
    }

}
