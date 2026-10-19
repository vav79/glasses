package ru.vasev.glasses.nav

import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Текст адреса со снимка — на телефоне, встроенной моделью ML Kit (латиница; русский, тайский и арабский
 * она не читает — для них кнопка «Распознать через ИИ»). Снимок никуда не уходит.
 */
class AddressOcr {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Строки текста через запятую; null — букв почти нет (не та письменность или текста нет). */
    suspend fun read(jpeg: ByteArray): String? = try {
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) } ?: return null
        val text = suspendCancellableCoroutine<String?> { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        val joined = text?.lines()?.map(String::trim)?.filter(String::isNotEmpty)?.joinToString(", ")?.take(MAX_LENGTH)
        val letters = joined?.count(Char::isLetter) ?: 0
        Log.d(TAG, "ocr letters=$letters")
        joined?.takeIf { letters >= MIN_LETTERS }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "ocr failed: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "Geocoder"
        const val MAX_LENGTH = 200
        const val MIN_LETTERS = 6
    }
}
