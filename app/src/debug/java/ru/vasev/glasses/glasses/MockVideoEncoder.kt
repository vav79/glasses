package ru.vasev.glasses.glasses

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Кодирует статичную картинку в короткое видео для MockCameraKit.setCameraFeed():
 * симулятор не запускает стрим без видеоисточника, а ffmpeg для подготовки файла не нужен.
 */
object MockVideoEncoder {

    private const val FPS = 15
    private const val SECONDS = 2
    private const val TIMEOUT_US = 10_000L

    /** Пишет видео в [output] (HEVC, если кодек есть, иначе H.264). Возвращает [output]. */
    fun encode(image: Bitmap, output: File): File {
        val width = image.width and 1.inv()
        val height = image.height and 1.inv()
        val mime = if (hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val tmp = File(output.parentFile, output.name + ".tmp")
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        val dst = Rect(0, 0, width, height)
        try {
            repeat(FPS * SECONDS) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawBitmap(image, null, dst, null)
                surface.unlockCanvasAndPost(canvas)
                track = drain(codec, muxer, info, track, endOfStream = false)
            }
            codec.signalEndOfInputStream()
            drain(codec, muxer, info, track, endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
        tmp.renameTo(output)
        return output
    }

    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        trackIndex: Int,
        endOfStream: Boolean,
    ): Int {
        var track = trackIndex
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return track
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return track
                }
            }
        }
    }

    private fun hasEncoder(mime: String): Boolean =
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
}
