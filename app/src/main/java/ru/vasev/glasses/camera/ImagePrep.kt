package ru.vasev.glasses.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import ru.vasev.glasses.BuildConfig
import java.io.ByteArrayOutputStream

/**
 * Подготовка снимка к отправке в модель — одинаково для всех источников:
 * поворот, уменьшение до [MAX_SIDE] по длинной стороне и перекодирование в «чистый» JPEG.
 * `Bitmap.compress` не пишет EXIF, поэтому метаданные исходника (модель камеры, время, GPS) не уходят.
 */
object ImagePrep {

    const val MAX_SIDE = 1024
    private const val QUALITY = 85
    private const val TAG = "Image"

    /** EXIF лежит в начале JPEG — для проверки хватает первых 64 КБ. */
    private const val HEADER_BYTES = 64 * 1024

    private fun java.io.InputStream.readHeader(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = read(buffer, total, limit - total)
            if (n < 0) break
            total += n
        }
        return buffer.copyOf(total)
    }

    /** Размер после уменьшения: длинная сторона ≤ [max], пропорции сохраняются, увеличение не делается. */
    fun scaledSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val scale = max.toFloat() / longest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** Есть ли в JPEG блок EXIF (сегмент APP1 «Exif\0\0») — для проверки, что метаданные не уходят. */
    fun hasExif(jpeg: ByteArray): Boolean {
        val marker = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
        outer@ for (i in 0..jpeg.size - marker.size) {
            for (j in marker.indices) if (jpeg[i + j] != marker[j]) continue@outer
            return true
        }
        return false
    }

    /** Кадр с камеры: [rotationDegrees] — насколько повернуть, чтобы картинка стала «как видит глаз». */
    fun fromBitmap(bitmap: Bitmap, rotationDegrees: Int = 0, source: String, original: ByteArray? = null): ByteArray {
        val (w, h) = scaledSize(bitmap.width, bitmap.height)
        val scaled = if (w != bitmap.width || h != bitmap.height) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val upright = if (rotationDegrees % 360 != 0) {
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        } else {
            scaled
        }
        return encode(upright, source, original)
    }

    /**
     * Фото из галереи. ImageDecoder сам учитывает поворот из EXIF и сразу декодирует в уменьшенном размере,
     * так что большие снимки не раздувают память.
     */
    fun fromUri(resolver: ContentResolver, uri: Uri): ByteArray {
        val originalHasExif = if (BuildConfig.DEBUG) {
            runCatching { resolver.openInputStream(uri)?.use { hasExif(it.readHeader(HEADER_BYTES)) } }.getOrNull()
        } else {
            null
        }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (w, h) = scaledSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return encode(bitmap, ImageSource.GALLERY.logName, null, originalHasExif)
    }

    private fun encode(bitmap: Bitmap, source: String, original: ByteArray?, originalHasExifHint: Boolean? = null): ByteArray {
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        if (BuildConfig.DEBUG) {
            val before = originalHasExifHint ?: original?.let(::hasExif)
            Log.d(
                TAG,
                "source=$source size=${bitmap.width}x${bitmap.height} jpeg=${jpeg.size / 1024}KB " +
                    "exif_in_original=${before ?: "-"} exif_in_sent=${hasExif(jpeg)}",
            )
        }
        return jpeg
    }
}
