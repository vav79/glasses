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
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Распознавание одной фразы с микрофона телефона через системный SpeechRecognizer. Вызывать с главного потока.
 *
 * Распознаватель один на всё время работы: если создавать и уничтожать его на каждую фразу, отключение старого
 * экземпляра от службы Google прилетает в новый как ERROR_SERVER_DISCONNECTED и обрывает непрерывный режим.
 * Но если служба распознавания умерла (процесс Google перезапустился — он общий с Google TTS; процесс со службой
 * жестов живёт днями), старый экземпляр больше не оживёт: тогда он пересоздаётся ([RecognizerRecovery]).
 */
class PhoneMicSpeechSource(private val context: Context) : SpeechSource {

    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(languageTag: String, talker: Talker, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Heard {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw SpeechException(L.s(R.string.speech_no_service))
        }
        val first = listenOnce(languageTag, onPartial, onLevel)
        val failure = first as? Outcome.Failed ?: return (first as Outcome.Ok).heard
        if (!RecognizerRecovery.needsNew(failure.error, failure.ready)) throw errorFor(failure.error)
        // Служба отвалилась до начала прослушивания — новый распознаватель и ещё одна попытка.
        Log.w(TAG, "speech: recognizer error=${failure.error} before ready — recreating SpeechRecognizer")
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        // Сразу после пересоздания служба ещё поднимается, и новый экземпляр получал тот же код 11 (проверено на S25).
        delay(RecognizerRecovery.RECREATE_PAUSE_MS)
        return when (val second = listenOnce(languageTag, onPartial, onLevel)) {
            is Outcome.Ok -> second.heard.also { Log.w(TAG, "speech: recreated recognizer works") }
            is Outcome.Failed -> {
                if (RecognizerRecovery.needsNew(second.error, second.ready)) {
                    Log.w(TAG, "speech: still failing after recreate, error=${second.error}")
                } else {
                    Log.w(TAG, "speech: recreated recognizer works (error=${second.error}: silence or a normal failure)")
                }
                throw errorFor(second.error)
            }
        }
    }

    private sealed interface Outcome {
        data class Ok(val heard: Heard) : Outcome

        /** [ready] — распознаватель успел сказать onReadyForSpeech (служба на связи). */
        data class Failed(val error: Int, val ready: Boolean) : Outcome
    }

    private suspend fun listenOnce(languageTag: String, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Outcome {
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        return suspendCancellableCoroutine { cont ->
            var endOfSpeechAt = 0L
            var ready = false
            cont.invokeOnCancellation { recognizer.cancel() }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (!cont.isActive) return
                    if (text.isEmpty()) {
                        cont.resume(Outcome.Failed(SpeechRecognizer.ERROR_NO_MATCH, ready = true))
                    } else {
                        val end = if (endOfSpeechAt != 0L) endOfSpeechAt else SystemClock.elapsedRealtime()
                        cont.resume(Outcome.Ok(Heard(text, end)))
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
                    if (cont.isActive) cont.resume(Outcome.Failed(error, ready))
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    ready = true
                }

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

    private companion object {
        const val TAG = "Audio"
    }
}

/** Когда распознаватель пересоздавать (чистые правила, тесты). */
object RecognizerRecovery {
    // Коды SpeechRecognizer (константы, чтобы правила проверялись на JVM без Android).
    const val ERROR_CLIENT = 5
    const val ERROR_SERVER_DISCONNECTED = 11

    /** Пауза между пересозданием и новой попыткой. */
    const val RECREATE_PAUSE_MS = 700L

    /**
     * Связь со службой потеряна ещё до onReadyForSpeech — служба умерла, старый экземпляр не оживёт.
     * После onReadyForSpeech те же коды — обычный обрыв фразы (его непрерывный режим проглатывает), не пересоздаём:
     * пересоздание на каждую фразу само даёт ERROR_SERVER_DISCONNECTED.
     */
    fun needsNew(error: Int, ready: Boolean): Boolean =
        !ready && (error == ERROR_SERVER_DISCONNECTED || error == ERROR_CLIENT)
}
