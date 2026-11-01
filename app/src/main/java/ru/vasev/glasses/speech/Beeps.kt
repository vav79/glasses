package ru.vasev.glasses.speech

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Короткие сигналы в очки (медиа-маршрут, как речь ассистента): «слушаю» после жеста и «отбой»,
 * если после него ничего не сказали. Тон синтезируется здесь же — без файлов в ресурсах.
 */
object Beeps {

    /** Один высокий тон: микрофон включается следом. */
    suspend fun listening() = play(listOf(880.0 to 120))

    /** Два тона вниз: вопрос отменён, запроса не было. */
    suspend fun cancelled() = play(listOf(660.0 to 90, 0.0 to 40, 440.0 to 130))

    private suspend fun play(tones: List<Pair<Double, Int>>) = withContext(Dispatchers.Default) {
        val pcm = tones.flatMap { (hz, ms) -> tone(hz, ms).asIterable() }.toShortArray()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(Speaker.ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.onFailure { Log.w(TAG, "beep failed: ${it.javaClass.simpleName}") }.getOrNull() ?: return@withContext
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            // Буфер Bluetooth добавляет задержку: ждём сам сигнал и немного сверху, чтобы микрофон его не услышал.
            delay(pcm.size * 1000L / RATE + TAIL_MS)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Синус с плавным началом и концом (без щелчков); 0 Гц — пауза. */
    private fun tone(hz: Double, ms: Int): ShortArray {
        val n = RATE * ms / 1000
        val fade = min(n / 4, RATE * 10 / 1000)
        return ShortArray(n) { i ->
            if (hz == 0.0) return@ShortArray 0
            val edge = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * hz * i / RATE) * edge * AMPLITUDE).toInt().toShort()
        }
    }

    private const val TAG = "Audio"
    private const val RATE = 16_000
    private const val AMPLITUDE = 0.35 * Short.MAX_VALUE
    private const val TAIL_MS = 150L
}
