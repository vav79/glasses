package ru.vasev.glasses.speech

import java.util.Locale

/** Куда звучит речь: [ME] — мне (позже — в очки), [PARTNER] — собеседнику (динамик телефона). */
enum class AudioChannel { ME, PARTNER }

/** Вывод синтезированной речи по каналам. */
interface AudioOutput {
    /** Возвращается после окончания озвучки (onDone у TTS). [onStart] — момент начала звука. */
    suspend fun speak(text: String, locale: Locale, channel: AudioChannel, onStart: () -> Unit = {})

    suspend fun voiceStatus(locale: Locale): VoiceStatus

    fun stop()
}

/** Сейчас оба канала идут в динамик телефона через общий TextToSpeech. */
class PhoneSpeakerOutput(private val speaker: Speaker) : AudioOutput {

    override suspend fun speak(text: String, locale: Locale, channel: AudioChannel, onStart: () -> Unit) =
        speaker.speak(text, locale, onStart)

    override suspend fun voiceStatus(locale: Locale): VoiceStatus = speaker.voiceStatus(locale)

    override fun stop() = speaker.stop()
}
