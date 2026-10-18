package ru.vasev.glasses.speech

import android.os.Build
import java.util.Locale

/** Куда звучит речь: [ME] — мне (гарнитура, позже — очки), [PARTNER] — собеседнику (динамик телефона). */
enum class AudioChannel { ME, PARTNER }

/** Вывод синтезированной речи по каналам. */
interface AudioOutput {
    /**
     * Возвращается после окончания озвучки (onDone у TTS). [onStart] — момент начала звука.
     * Пока фраза звучит, музыка других приложений приглушена.
     */
    suspend fun speak(text: String, locale: Locale, channel: AudioChannel, onStart: () -> Unit = {})

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
) : AudioOutput {

    override suspend fun speak(text: String, locale: Locale, channel: AudioChannel, onStart: () -> Unit) {
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
                    speaker.speakTo(text, locale, builtin, onStart) { routed ->
                        router.log("out PARTNER", "playing on: ${router.describe(routed)}")
                    }
                    return
                }
            }
            val expected = if (headset?.output != null) "headset \"${headset.name}\"" else "phone speaker"
            router.log("out $channel", "$expected, system route: ${currentRoute()}; ${router.state()}")
            speaker.speak(text, locale, onStart)
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
}
