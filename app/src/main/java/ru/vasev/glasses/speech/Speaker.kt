package ru.vasev.glasses.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Озвучка ответов по-русски через системный TextToSpeech. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    @Volatile
    private var pending: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
        })
    }

    /** Говорит текст и возвращается, когда речь закончилась или была остановлена. */
    suspend fun speak(text: String) {
        if (!ready.await()) throw SpeechException("Синтез речи недоступен на телефоне.")
        val lang = tts.setLanguage(Locale.forLanguageTag("ru-RU"))
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw SpeechException("Нет русского голоса. Установите его в настройках синтеза речи.")
        }
        suspendCancellableCoroutine { cont ->
            pending = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    fun stop() {
        tts.stop()
        finish()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun finish() {
        pending?.invoke()
        pending = null
    }
}
