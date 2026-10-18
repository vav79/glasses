package ru.vasev.glasses.speech

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Пока приложение говорит, музыка и подкасты других приложений приглушаются (transient focus с ducking)
 * и после ответа продолжаются сами. Захваты считаются: ответ ассистента держит фокус целиком,
 * а каждая фраза внутри — ещё раз, и между предложениями музыка не возвращается к полной громкости.
 */
class AudioFocus(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(Speaker.ATTRIBUTES)
        .setOnAudioFocusChangeListener { }
        .build()
    private var holders = 0

    @Synchronized
    fun acquire() {
        if (holders++ == 0) {
            val result = audio.requestAudioFocus(request)
            Log.d(TAG, "focus duck: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "denied($result)"}")
        }
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        if (--holders == 0) {
            audio.abandonAudioFocusRequest(request)
            Log.d(TAG, "focus released")
        }
    }

    private companion object {
        const val TAG = "Audio"
    }
}
