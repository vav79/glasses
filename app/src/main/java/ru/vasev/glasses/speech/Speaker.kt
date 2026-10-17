package ru.vasev.glasses.speech

import android.content.Context
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

enum class VoiceStatus { READY, NOT_DOWNLOADED, UNSUPPORTED }

/** Синтез речи через системный TextToSpeech. Один экземпляр на приложение. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val cacheDir = context.cacheDir

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var pending: (() -> Unit)? = null

    @Volatile
    private var pendingStart: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(ATTRIBUTES)
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
        if (!ready.await()) throw SpeechException(L.s(R.string.tts_unavailable))
        val lang = tts.setLanguage(locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw SpeechException(L.s(R.string.tts_no_voice, locale.getDisplayLanguage(LanguageSettings.current.value.locale)))
        }
        suspendCancellableCoroutine { cont ->
            pendingStart = onStart
            pending = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    /**
     * Говорит [text] строго через устройство [device] (например, динамик телефона при подключённой гарнитуре).
     * TextToSpeech не умеет выбирать устройство, поэтому речь синтезируется в файл и проигрывается MediaPlayer
     * с `setPreferredDevice` и атрибутами медиа (громкость медиа, а не звонка).
     * [onRouted] получает устройство, на котором звук реально идёт: в момент старта и при каждой смене маршрута.
     */
    suspend fun speakTo(
        text: String,
        locale: Locale,
        device: AudioDeviceInfo,
        onStart: () -> Unit = {},
        onRouted: (AudioDeviceInfo?) -> Unit = {},
    ) {
        if (!ready.await()) throw SpeechException(L.s(R.string.tts_unavailable))
        val lang = tts.setLanguage(locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw SpeechException(L.s(R.string.tts_no_voice, locale.getDisplayLanguage(LanguageSettings.current.value.locale)))
        }
        val file = File(cacheDir, "tts_out.wav")
        // 1. Синтез в файл.
        suspendCancellableCoroutine { cont ->
            pending = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { tts.stop() }
            tts.synthesizeToFile(text, null, file, UUID.randomUUID().toString())
        }
        // 2. Воспроизведение на выбранное устройство.
        val player = MediaPlayer()
        this.player = player
        try {
            player.setAudioAttributes(MEDIA_ATTRIBUTES)
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setPreferredDevice(device)
            player.addOnRoutingChangedListener({ routing -> onRouted(routing.routedDevice) }, null)
            suspendCancellableCoroutine { cont ->
                player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                cont.invokeOnCancellation { runCatching { player.stop() } }
                player.start()
                onStart()
                onRouted(player.routedDevice)
            }
        } finally {
            this.player = null
            player.release()
        }
    }

    fun stop() {
        tts.stop()
        player?.let { runCatching { it.stop() } }
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

        /** Речь ассистента: обычный медиа-маршрут (при подключённой гарнитуре — в неё). */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** Речь для собеседника через динамик: громкость медиа. */
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
