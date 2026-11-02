package ru.vasev.glasses.video

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer

/**
 * Один кусок записи — MP4 прямо в галерее (`Movies/Sputnik`, MediaStore, без разрешений): пока пишется —
 * «в работе» (IS_PENDING), после [close] — виден всем. HEVC с очков пишется как есть, без перекодирования.
 * Со звуком файл стартует, когда известны обе дорожки; кадры до этого ждут в памяти (звук приходит за ~0,1 с).
 * Вызовы из разных потоков (видео — корутина DAT, звук — свой поток), поэтому всё под одной блокировкой.
 */
class Mp4Segment(private val resolver: ContentResolver, name: String, private val withAudio: Boolean) {

    val uri: Uri
    private val file: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var closed = false
    private val pending = ArrayDeque<Sample>()
    private var pendingBytes = 0L

    /** Записано байт (видео + звук). */
    @Volatile
    var bytes = 0L
        private set

    private class Sample(val audio: Boolean, val data: ByteArray, val info: MediaCodec.BufferInfo)

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("MediaStore insert failed")
        file = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("cannot open $uri")
        muxer = MediaMuxer(file.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Видео: описание потока (VPS/SPS/PPS) из ключевого кадра. */
    @Synchronized
    fun setVideo(parameterSets: ByteArray, width: Int, height: Int) {
        if (videoTrack >= 0) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets))
        }
        videoTrack = muxer.addTrack(format)
        startIfReady()
    }

    /** Звук: формат AAC от кодировщика. */
    @Synchronized
    fun setAudio(format: MediaFormat) {
        if (!withAudio || started || audioTrack >= 0) return
        audioTrack = muxer.addTrack(format)
        startIfReady()
    }

    /** Звук так и не пришёл — файл стартует только с видео (иначе кадры копились бы в памяти). */
    @Synchronized
    fun startWithoutAudio() {
        if (started || videoTrack < 0) return
        Log.w(TAG, "segment: no audio format, video only")
        start()
    }

    @Synchronized
    fun writeVideo(data: ByteArray, ptsUs: Long, key: Boolean) {
        val info = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
        write(Sample(audio = false, data, info))
    }

    @Synchronized
    fun writeAudio(data: ByteArray, ptsUs: Long) {
        if (!withAudio) return
        val info = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, 0) }
        write(Sample(audio = true, data, info))
    }

    private fun write(sample: Sample) {
        if (closed) return
        if (!started) {
            // Звук ещё не готов: держим, но не больше нескольких мегабайт (≈ 1 мин видео).
            if (pendingBytes < MAX_PENDING_BYTES) {
                pending += sample
                pendingBytes += sample.data.size
            }
            return
        }
        val track = if (sample.audio) audioTrack else videoTrack
        if (track < 0) return
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), sample.info)
        bytes += sample.data.size
    }

    private fun startIfReady() {
        if (started || videoTrack < 0 || (withAudio && audioTrack < 0)) return
        start()
    }

    private fun start() {
        muxer.start()
        started = true
        while (pending.isNotEmpty()) write(pending.removeFirst())
        pendingBytes = 0
    }

    /** Закрыть кусок: виден в галерее; пустой — удаляется. Возвращает uri, если в нём есть кадры. */
    @Synchronized
    fun close(): Uri? {
        if (closed) return null
        closed = true
        val hasData = started && bytes > 0
        runCatching { if (started) muxer.stop() }.onFailure { Log.w(TAG, "segment: muxer stop failed: ${it.javaClass.simpleName}") }
        runCatching { muxer.release() }
        runCatching { file.close() }
        return if (hasData) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    companion object {
        private const val TAG = "Video"
        const val RELATIVE_PATH = "Movies/Sputnik/"
        private const val MAX_PENDING_BYTES = 8L * 1024 * 1024
    }
}
