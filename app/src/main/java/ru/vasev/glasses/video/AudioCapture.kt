package ru.vasev.glasses.video

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log

/**
 * Звук записи: микрофон ([device] — гарнитура / очки по Bluetooth, null — телефон) → AAC.
 * Время сэмплов — по числу поданных в кодировщик отсчётов, а пока [feeding] = false (поток видео на паузе),
 * звук не подаётся и время стоит: так звук и видео в куске идут вместе (видео паузы вырезает — SegmentTimeline).
 * Свой поток: чтение микрофона блокирующее.
 */
class AudioCapture(
    private val device: AudioDeviceInfo?,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (data: ByteArray, ptsUs: Long) -> Unit,
) {

    /** Подавать ли звук в кодировщик (false — пауза видео). */
    @Volatile
    var feeding = false

    /** Время следующего сэмпла (мкс): начало нового куска. */
    @Volatile
    var nextPtsUs = 0L
        private set

    private val sampleRate = if (device != null) BLUETOOTH_RATE else PHONE_RATE
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Описание входа — для логов. */
    val describe: String get() = if (device != null) "bluetooth \"${device.productName}\" ${sampleRate}Hz" else "phone ${sampleRate}Hz"

    @SuppressLint("MissingPermission") // RECORD_AUDIO проверяет VideoRecorder перед стартом
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (device != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.CAMCORDER
        val record = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate / 5 * 2))
        if (device != null) record.setPreferredDevice(device)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (device != null) 32_000 else 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        record.startRecording()
        running = true
        thread = Thread({ loop(record, encoder) }, "video-audio").apply { start() }
        Log.d(TAG, "audio: started $describe")
    }

    private fun loop(record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(sampleRate / 50 * 2) // 20 мс, 16 бит моно
        var fedFrames = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                if (feeding) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!
                        buffer.clear()
                        buffer.put(pcm, 0, read)
                        val pts = fedFrames * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(index, 0, read, pts, 0)
                        fedFrames += read / 2
                        nextPtsUs = fedFrames * 1_000_000L / sampleRate
                    }
                }
                while (true) {
                    val out = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(encoder.outputFormat)
                        out >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                val buffer = encoder.getOutputBuffer(out)!!
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(data)
                                onSample(data, info.presentationTimeUs)
                            }
                            encoder.releaseOutputBuffer(out, false)
                        }
                        else -> break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio: stopped by ${e.javaClass.simpleName}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
        Log.d(TAG, "audio: stopped")
    }

    private companion object {
        const val TAG = "Video"
        const val PHONE_RATE = 48_000
        const val BLUETOOTH_RATE = 16_000
    }
}
