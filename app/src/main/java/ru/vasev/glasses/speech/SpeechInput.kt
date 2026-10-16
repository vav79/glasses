package ru.vasev.glasses.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpeechException(message: String) : Exception(message)

/** Распознавание одной фразы на русском через системный SpeechRecognizer. Вызывать с главного потока. */
class SpeechInput(private val context: Context) {

    suspend fun listen(onPartial: (String) -> Unit = {}): String {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw SpeechException("На телефоне нет службы распознавания речи (нужно приложение Google).")
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { recognizer.cancel() }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (!cont.isActive) return
                        if (text.isEmpty()) {
                            cont.resumeWithException(SpeechException("Не расслышал вопрос. Попробуйте ещё раз."))
                        } else {
                            cont.resume(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle) {
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.let(onPartial)
                    }

                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resumeWithException(SpeechException(errorText(error)))
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    },
                )
            }
        } finally {
            recognizer.destroy()
        }
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Не расслышал вопрос. Попробуйте ещё раз."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Нет сети для распознавания речи."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, попробуйте через секунду."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Русский язык для распознавания недоступен на телефоне."
        else -> "Ошибка распознавания речи (код $error)."
    }
}
