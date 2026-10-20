package ru.vasev.glasses.speech

import android.os.Build
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.vasev.glasses.profile.SpeechPace
import java.util.Locale

/** Куда звучит речь: [ME] — мне (гарнитура, позже — очки), [PARTNER] — собеседнику (динамик телефона). */
enum class AudioChannel {
    ME,
    PARTNER,
    ;

    /** Скорость речи: мне — из «Стиля общения» ([override] — пробный ответ в шторке до «Сохранить»), собеседнику — всегда обычная. */
    fun rate(pace: SpeechPace, override: Float? = null): Float = when (this) {
        ME -> override ?: pace.rate
        PARTNER -> SpeechPace.NORMAL.rate
    }
}

/** Вывод синтезированной речи по каналам. */
interface AudioOutput {
    /**
     * Возвращается после окончания озвучки (onDone у TTS). [onStart] — момент начала звука.
     * Пока фраза звучит, музыка других приложений приглушена.
     */
    suspend fun speak(text: String, locale: Locale, channel: AudioChannel, rate: Float? = null, onStart: () -> Unit = {})

    /** Держать музыку приглушённой между фразами одного ответа — до [releaseFocus]. */
    fun holdFocus()

    fun releaseFocus()

    suspend fun voiceStatus(locale: Locale): VoiceStatus

    fun stop()
}

/**
 * Вывод через телефон и Bluetooth-гарнитуру.
 * [AudioChannel.ME] — обычный медиа-маршрут: при подключённой гарнитуре звук идёт в неё, иначе в динамик.
 * [AudioChannel.PARTNER] — всегда динамик телефона; при подключённой гарнитуре это делается принудительно.
 */
class PhoneSpeakerOutput(
    private val speaker: Speaker,
    private val router: AudioRouter,
    private val focus: AudioFocus,
    private val navigator: NavigatorVoice,
    private val pace: () -> SpeechPace,
) : AudioOutput {

    /**
     * Мы уступаем навигатору: перед фразой ждём, пока он договорит; заговорил посреди нашей фразы — замолкаем
     * и повторяем её, когда он закончит (один раз, иначе при длинной подсказке фраза потерялась бы совсем).
     */
    override suspend fun speak(text: String, locale: Locale, channel: AudioChannel, rate: Float?, onStart: () -> Unit) {
        val speechRate = channel.rate(pace(), rate)
        var started = false
        val once = {
            if (!started) {
                started = true
                onStart()
            }
        }
        repeat(ATTEMPTS) { attempt ->
            navigator.awaitQuiet()
            val interrupted = coroutineScope {
                val watcher = launch {
                    navigator.speaking.first { it }
                    router.log("out $channel", "navigator started speaking — yielding")
                    speaker.stop()
                }
                try {
                    speakOnce(text, locale, channel, speechRate, once)
                } finally {
                    watcher.cancel()
                }
                navigator.speaking.value
            }
            if (!interrupted || attempt == ATTEMPTS - 1) return
        }
    }

    private suspend fun speakOnce(text: String, locale: Locale, channel: AudioChannel, rate: Float, onStart: () -> Unit) {
        // Пока жив SCO-канал микрофона гарнитуры, телефон «в звонке»: звук ушёл бы в гарнитуру с громкостью звонка.
        // Исключение — голосовое «стоп» во время ответа: микрофон держится намеренно (AudioRouter.holdMic).
        router.releaseMic()
        router.awaitRouteSettled()
        focus.acquire()
        try {
            val headset = router.headset.value
            if (channel == AudioChannel.PARTNER && headset?.output != null) {
                val builtin = router.builtinSpeaker()
                if (builtin != null) {
                    router.log("out PARTNER", "phone speaker requested (headset connected); ${router.state()}")
                    speaker.speakTo(text, locale, builtin, rate, onStart) { routed ->
                        router.log("out PARTNER", "playing on: ${router.describe(routed)}")
                    }
                    return
                }
            }
            val expected = if (headset?.output != null) "headset \"${headset.name}\"" else "phone speaker"
            router.log("out $channel", "$expected, system route: ${currentRoute()}; ${router.state()}")
            speaker.speak(text, locale, rate, onStart)
        } finally {
            focus.release()
        }
    }

    override fun holdFocus() = focus.acquire()

    override fun releaseFocus() = focus.release()

    override suspend fun voiceStatus(locale: Locale): VoiceStatus = speaker.voiceStatus(locale)

    override fun stop() = speaker.stop()

    private fun currentRoute(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            router.devicesFor(Speaker.ATTRIBUTES).joinToString { router.describe(it) }.ifEmpty { "unknown" }
        } else {
            "unknown (Android < 13)"
        }

    private companion object {
        /** Фраза и один повтор после подсказки навигатора. */
        const val ATTEMPTS = 2
    }
}
