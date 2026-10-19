package ru.vasev.glasses.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Говорит ли сейчас навигатор (Google, Яндекс…): его подсказки играют с usage `ASSISTANCE_NAVIGATION_GUIDANCE`.
 * Чужие воспроизведения видны без разрешений (обезличенно, но с атрибутами). Наша речь — `USAGE_ASSISTANT`,
 * поэтому себя мы здесь не видим. Наша озвучка ждёт, пока навигатор договорит, и замолкает, если он заговорил.
 */
class NavigatorVoice(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val _speaking = MutableStateFlow(check(audio.activePlaybackConfigurations))

    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    init {
        audio.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    val now = check(configs)
                    if (now != _speaking.value) Log.d(TAG, "navigator voice ${if (now) "started" else "ended"}")
                    _speaking.value = now
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Дождаться тишины навигатора, но не дольше [maxMs]; true — тихо. */
    suspend fun awaitQuiet(maxMs: Long = MAX_WAIT_MS): Boolean {
        if (!_speaking.value) return true
        Log.d(TAG, "waiting for the navigator to finish")
        return withTimeoutOrNull(maxMs) { _speaking.first { !it } } != null
    }

    private fun check(configs: List<AudioPlaybackConfiguration>): Boolean =
        configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }

    private companion object {
        const val TAG = "Audio"
        const val MAX_WAIT_MS = 15_000L
    }
}
