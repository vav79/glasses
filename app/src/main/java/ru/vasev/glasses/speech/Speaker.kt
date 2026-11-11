package ru.vasev.glasses.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class VoiceStatus { READY, NOT_DOWNLOADED, UNSUPPORTED }

/**
 * Синтез речи через системный TextToSpeech. Один на приложение, но сам TextToSpeech пересоздаётся, если движок
 * отвалился ([TtsRecovery]): процесс со службой жестов живёт днями, а движок система перезапускает сама —
 * старый экземпляр после этого «not bound to TTS engine» навсегда.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = context.cacheDir

    @Volatile
    private var player: MediaPlayer? = null

    /** Звучит фраза собеседнику через динамик (USAGE_MEDIA): жесты не должны принять её за чужую музыку. */
    val playingToPartner: Boolean get() = player != null

    /** Фраза, которую сейчас говорит (или синтезирует в файл) движок. */
    @Volatile
    private var current: Utterance? = null

    /** Языки, которыми уже говорили: их «нет голоса» — отвал движка, а не отсутствие голоса. */
    private val workedLocales: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            current?.takeIf { it.id == utteranceId }?.started()
        }

        override fun onDone(utteranceId: String?) = finish(utteranceId, null)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, null)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finish(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, errorCode)

        private fun finish(id: String?, error: Int?) {
            current?.takeIf { it.id == id }?.finish(error)
        }
    }

    @Volatile
    private var engine = Engine()

    private inner class Engine {
        val ready = CompletableDeferred<Boolean>()
        val tts: TextToSpeech = TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

        init {
            tts.setAudioAttributes(ATTRIBUTES)
            tts.setOnUtteranceProgressListener(listener)
        }

        suspend fun awaitReady(): Boolean = withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } ?: false
    }

    private class Utterance(val id: String, private val onStart: () -> Unit) {
        val started = CompletableDeferred<Unit>()

        /** null — договорили или остановили, иначе код onError. */
        val result = CompletableDeferred<Int?>()

        fun started() {
            if (started.complete(Unit)) onStart()
        }

        fun finish(error: Int?) {
            started.complete(Unit)
            result.complete(error)
        }
    }

    /** Есть ли голос для языка: установлен, нужно скачать или не поддерживается движком. */
    suspend fun voiceStatus(locale: Locale): VoiceStatus {
        if (!engine.awaitReady() || engine.tts.voices == null) {
            recreate(TtsFailure.LANGUAGE_LOST, "voice status")
            if (!engine.awaitReady()) return VoiceStatus.UNSUPPORTED
        }
        val tts = engine.tts
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
     * Говорит [text] голосом для [locale] с темпом [rate] (1.0 — обычный) и возвращается, когда речь закончилась (onDone) или была остановлена.
     * [onStart] вызывается в момент начала звука. Движок отвалился — пересоздаётся, фраза повторяется один раз.
     */
    suspend fun speak(text: String, locale: Locale, rate: Float = 1.0f, onStart: () -> Unit = {}) {
        ensureSpoken(say(text, locale, rate, onStart, file = null), locale)
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
        rate: Float = 1.0f,
        onStart: () -> Unit = {},
        onRouted: (AudioDeviceInfo?) -> Unit = {},
    ) {
        val file = File(cacheDir, "tts_out.wav")
        // 1. Синтез в файл (onStart синтеза — не начало звука, его не передаём).
        ensureSpoken(say(text, locale, rate, onStart = {}, file = file), locale)
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

    private suspend fun say(text: String, locale: Locale, rate: Float, onStart: () -> Unit, file: File?): TtsAttempt =
        TtsRecovery.speak(
            attempt = { attempt(text, locale, rate, onStart, file) },
            recreate = { reason -> recreate(reason, locale.toLanguageTag()) },
        )

    /** Одна попытка: фраза принята движком, начался звук (или синтез) и закончился без onError. */
    private suspend fun attempt(text: String, locale: Locale, rate: Float, onStart: () -> Unit, file: File?): TtsAttempt {
        val engine = engine
        if (!engine.awaitReady()) return TtsAttempt.Failed(TtsFailure.INIT)
        val tts = engine.tts
        val tag = locale.toLanguageTag()
        val lang = tts.setLanguage(locale)
        // voices — запрос к движку; только при отказе, чтобы отличить «не привязан» от «голоса нет».
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            TtsRecovery.language(lang, bound = tts.voices != null, workedBefore = tag in workedLocales)?.let { return it }
        }
        val utterance = Utterance(UUID.randomUUID().toString(), onStart)
        current = utterance
        try {
            tts.setSpeechRate(rate)
            val accepted = if (file == null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance.id)
            } else {
                tts.synthesizeToFile(text, null, file, utterance.id)
            }
            // ERROR — фраза не принята: onDone не придёт, ждать нечего.
            if (accepted != TextToSpeech.SUCCESS) return TtsAttempt.Failed(TtsFailure.CALL_ERROR)
            if (withTimeoutOrNull(TtsRecovery.START_TIMEOUT_MS) { utterance.started.await() } == null) {
                runCatching { tts.stop() }
                return TtsAttempt.Failed(TtsFailure.NO_START)
            }
            val error = utterance.result.await()
            if (error != null) {
                Log.w(TAG, "tts: onError code=$error")
                return TtsAttempt.Failed(TtsFailure.ON_ERROR)
            }
            workedLocales += tag
            return TtsAttempt.Spoken
        } catch (e: CancellationException) {
            runCatching { tts.stop() }
            throw e
        } finally {
            if (current === utterance) current = null
        }
    }

    /** Новый TextToSpeech вместо отвалившегося: те же атрибуты и слушатель, старый — shutdown. */
    private suspend fun recreate(reason: TtsFailure, what: String) {
        val old = engine
        Log.w(TAG, "tts: engine=${engineName(old.tts)} failed (${reason.log}, $what) — recreating TextToSpeech")
        val fresh = Engine()
        engine = fresh
        runCatching {
            old.tts.stop()
            old.tts.shutdown()
        }
        val ok = fresh.awaitReady()
        Log.w(TAG, "tts: recreated, ready=$ok engine=${engineName(fresh.tts)}")
    }

    private fun ensureSpoken(result: TtsAttempt, locale: Locale) {
        when (result) {
            TtsAttempt.Spoken -> Unit
            TtsAttempt.NoVoice ->
                throw SpeechException(L.s(R.string.tts_no_voice, locale.getDisplayLanguage(LanguageSettings.current.value.locale)))
            is TtsAttempt.Failed -> {
                Log.w(TAG, "tts: still failing after recreate (${result.reason.log})")
                throw SpeechException(L.s(R.string.tts_unavailable))
            }
        }
    }

    fun stop() {
        runCatching { engine.tts.stop() }
        player?.let { runCatching { it.stop() } }
        // У отвязанного движка stop() не даёт onStop — фразу завершаем сами, чтобы ожидание не повисло.
        current?.finish(null)
    }

    /** Пакет движка синтеза, которым приложение реально говорит (например, com.google.android.tts). */
    val enginePackage: String? get() = engineName(engine.tts)

    fun shutdown() {
        runCatching {
            engine.tts.stop()
            engine.tts.shutdown()
        }
    }

    /**
     * Движок, к которому привязан TextToSpeech. Системная настройка (defaultEngine) может с ним не совпадать:
     * на S25 по умолчанию Samsung, а привязан Google — TextToSpeech берёт другой, если основной не поднялся.
     */
    private fun engineName(tts: TextToSpeech): String? =
        runCatching { TextToSpeech::class.java.getMethod("getCurrentEngine").invoke(tts) as? String }.getOrNull()
            ?: tts.defaultEngine

    companion object {
        private const val TAG = "Audio"

        /** Ожидание инициализации движка (обычно < 1 с; без ответа — считаем, что не поднялся). */
        private const val READY_TIMEOUT_MS = 10_000L

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
