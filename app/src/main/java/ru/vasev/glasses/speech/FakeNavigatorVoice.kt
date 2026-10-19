package ru.vasev.glasses.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Только debug: «подсказка навигатора» — тихий тон с usage `ASSISTANCE_NAVIGATION_GUIDANCE`, чтобы проверить,
 * что наша речь уступает навигатору, не подгадывая момент, когда заговорит Google Maps.
 */
object FakeNavigatorVoice {

    suspend fun play(durationMs: Int = 3_000) = withContext(Dispatchers.IO) {
        val rate = 16_000
        val samples = rate * durationMs / 1000
        val pcm = ShortArray(samples) { i -> (sin(2 * PI * 440 * i / rate) * 2_000).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        try {
            track.write(pcm, 0, samples)
            track.play()
            Thread.sleep(durationMs.toLong() + 200)
        } finally {
            track.stop()
            track.release()
        }
    }
}
