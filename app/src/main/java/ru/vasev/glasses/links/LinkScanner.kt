package ru.vasev.glasses.links

import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ссылка, найденная на снимке: [fromQr] — в QR-коде, иначе напечатана текстом. */
data class FoundLink(val info: LinkInfo, val fromQr: Boolean)

/**
 * QR-коды и напечатанные адреса на снимке — распознаются на телефоне встроенными моделями ML Kit, снимок
 * никуда не отправляется. Ничего не открывает: только находит и разбирает ([LinkCheck]).
 */
class LinkScanner {

    private val barcodes by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun scan(jpeg: ByteArray): List<FoundLink> = try {
        val started = System.currentTimeMillis()
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
            ?: return emptyList()
        val image = InputImage.fromBitmap(bitmap, 0)
        val found = coroutineScope {
            val qr = async {
                val codes = barcodes.process(image).await()
                Log.d(TAG, "qr codes=${codes.size} without_text=${codes.count { it.rawValue == null }}")
                codes.mapNotNull { (it.rawValue ?: textOf(it.rawBytes))?.takeIf(String::isNotBlank) }
                    .map { FoundLink(LinkCheck.analyze(it), fromQr = true) }
            }
            val printed = async {
                LinkCheck.findInText(text.process(image).await().text)
                    .map { FoundLink(LinkCheck.analyze(it), fromQr = false) }
            }
            qr.await() + printed.await()
        }
        val links = found
            .filter { it.info.kind != LinkKind.UNKNOWN }
            // Один адрес и в QR, и подписью под ним — показываем один раз (QR важнее).
            .distinctBy { it.info.openUri ?: it.info.raw }
            .take(MAX_LINKS)
        Log.d(TAG, "scan links=${links.size} qr=${links.count { it.fromQr }} ms=${System.currentTimeMillis() - started}")
        links
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "scan failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    /**
     * Текст QR, который ML Kit не смог декодировать сам (кириллица без указания кодировки): платёжки по ГОСТ
     * `ST00011` — в windows-1251, остальное — UTF-8, а если байты не UTF-8 — windows-1251.
     */
    private fun textOf(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val head = String(bytes, 0, minOf(bytes.size, 7), Charsets.US_ASCII)
        val cp1251 = java.nio.charset.Charset.forName("windows-1251")
        if (head == "ST00011") return String(bytes, cp1251)
        // Строгий UTF-8; не вышло — windows-1251 (встречается и под заголовком ST00012, вопреки ГОСТ).
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, cp1251)
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        const val TAG = "Links"
        const val MAX_LINKS = 5
    }
}
