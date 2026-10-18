package ru.vasev.glasses.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent

/**
 * Кнопка гарнитуры во время ответа ассистента. Пока ответ идёт, у приложения есть медиасессия
 * в состоянии «играет» — Android отдаёт ей нажатия кнопок гарнитуры. После ответа сессия уничтожается
 * (неактивная, она всё равно перехватывала бы кнопки), и кнопки снова управляют музыкой.
 * Вызывать на главном потоке (колбэки — на нём же).
 */
class HeadsetButtons(private val context: Context) {

    private var session: MediaSession? = null

    private var onPress: (() -> Unit)? = null

    private fun newSession() = MediaSession(context, "GlassesAnswer").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = keyEvent(mediaButtonIntent) ?: return false
                if (event.keyCode !in KEYS) return false
                // Срабатываем сразу на нажатие, без ожидания двойного щелчка.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) press("key ${KeyEvent.keyCodeToString(event.keyCode)}")
                return true
            }

            // Некоторые гарнитуры и Bluetooth-стек присылают команды транспорта, а не коды клавиш.
            override fun onPause() = press("pause")
            override fun onPlay() = press("play")
            override fun onStop() = press("stop")
        })
    }

    /**
     * Android отдаёт кнопки сессии приложения, которое последним само воспроизводило звук. Речь TTS играет
     * в процессе движка синтеза, а не в нашем — поэтому, пока идёт ответ, в нашем процессе крутится
     * беззвучная дорожка: так кнопки гарнитуры приходят нам, а не последнему видео- или музыкальному приложению.
     */
    private var silence: AudioTrack? = null

    private fun startSilence() {
        if (silence != null) return
        val frames = SILENCE_RATE / 2
        silence = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(Speaker.ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENCE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
                .apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                    play()
                }
        }.onFailure { Log.w(TAG, "silent track failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun stopSilence() {
        silence?.let { runCatching { it.stop() }; it.release() }
        silence = null
    }

    /** Ловить нажатия, пока не вызван [stop]. */
    fun listen(onPress: () -> Unit) {
        this.onPress = onPress
        startSilence()
        val current = session ?: newSession().also { session = it }
        current.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                .build(),
        )
        current.isActive = true
    }

    fun stop() {
        onPress = null
        stopSilence()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
    }

    private fun press(what: String) {
        val action = onPress ?: return
        Log.d(TAG, "headset button: $what")
        action()
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private companion object {
        const val TAG = "Audio"
        const val SILENCE_RATE = 8000
        val KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
