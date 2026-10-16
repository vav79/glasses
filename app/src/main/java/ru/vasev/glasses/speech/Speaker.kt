package ru.vasev.glasses.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

enum class VoiceStatus { READY, NOT_DOWNLOADED, UNSUPPORTED }

/** Синтез речи через системный TextToSpeech. Один экземпляр на приложение. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()

    @Volatile
    private var pending: (() -> Unit)? = null

    @Volatile
    private var pendingStart: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                pendingStart?.invoke()
                pendingStart = null
            }

            override fun onDone(utteranceId: String?) = finish()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
        })
    }

    /** Есть ли голос для языка: установлен, нужно скачать или не поддерживается движком. */
    suspend fun voiceStatus(locale: Locale): VoiceStatus {
        if (!ready.await()) return VoiceStatus.UNSUPPORTED
        val available = tts.isLanguageAvailable(locale)
        if (available == TextToSpeech.LANG_NOT_SUPPORTED) return VoiceStatus.UNSUPPORTED
        if (available == TextToSpeech.LANG_MISSING_DATA) return VoiceStatus.NOT_DOWNLOADED
        val voices = tts.voices.orEmpty().filter { it.locale.language == locale.language }
        if (voices.isNotEmpty() && voices.all { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }) {
            return VoiceStatus.NOT_DOWNLOADED
        }
        return VoiceStatus.READY
    }

    /**
     * Говорит [text] голосом для [locale] и возвращается, когда речь закончилась (onDone) или была остановлена.
     * [onStart] вызывается в момент начала звука.
     */
    suspend fun speak(text: String, locale: Locale, onStart: () -> Unit = {}) {
        if (!ready.await()) throw SpeechException("Синтез речи недоступен на телефоне.")
        val lang = tts.setLanguage(locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw SpeechException("Нет голоса для языка «${locale.getDisplayLanguage(RUSSIAN)}».")
        }
        suspendCancellableCoroutine { cont ->
            pendingStart = onStart
            pending = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    fun stop() {
        tts.stop()
        finish()
    }

    /** Пакет движка синтеза, которым приложение реально говорит (например, com.google.android.tts). */
    val enginePackage: String? get() = tts.defaultEngine

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun finish() {
        pendingStart = null
        pending?.invoke()
        pending = null
    }

    companion object {
        val RUSSIAN: Locale = Locale.forLanguageTag("ru-RU")
    }
}
