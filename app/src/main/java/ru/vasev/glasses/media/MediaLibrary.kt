package ru.vasev.glasses.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.ShotStore
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Снимок в «Медиа»: файл на телефоне, когда снят, откуда (если известно), из чата ли. */
data class MediaItem(val file: File, val takenAt: Instant, val source: ImageSource?, val fromChat: Boolean)

/**
 * «Медиа»: снимки из чатов (`files/media/chat`, остаются и после удаления чата) и лента последних снимков
 * (`files/shots`, [ShotStore]). Один и тот же снимок лежит в обеих папках — показывается один раз
 * (сравнение по содержимому). Только на телефоне; в галерею — по «Сохранить».
 */
class MediaLibrary(context: Context, private val shots: ShotStore) {

    private val chatDir = File(context.filesDir, "media/chat")
    private val resolver = context.contentResolver

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())

    /** От новых к старым; обновляется [refresh]. */
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val fromChats = chatDir.listFiles().orEmpty().filter { it.extension == "jpg" }.map { file ->
            val millis = file.name.substringBefore('_').toLongOrNull() ?: file.lastModified()
            MediaItem(file, Instant.ofEpochMilli(millis), null, fromChat = true)
        }
        val fromShots = shots.shots.value.map { shot ->
            MediaItem(File(shots.dir, shot.fileName), shot.takenAt, shot.source, fromChat = false)
        }.filter { it.file.exists() }
        // Снимок, отправленный в чат, есть и в ленте: оставляем копию из чата (она не удаляется сама), источник — из ленты.
        val shotByHash = fromShots.associateBy { hash(it.file) }
        val chatHashes = mutableSetOf<String>()
        val merged = fromChats.map { item ->
            val h = hash(item.file)
            chatHashes += h
            shotByHash[h]?.let { item.copy(source = it.source, takenAt = it.takenAt) } ?: item
        } + fromShots.filter { hash(it.file) !in chatHashes }
        _items.value = merged.sortedByDescending { it.takenAt }
    }

    /** Удалить снимок из «Медиа» (из обеих папок). Чат, где он был, покажет обмен без снимка. */
    suspend fun delete(item: MediaItem) = withContext(Dispatchers.IO) {
        val h = hash(item.file)
        chatDir.listFiles().orEmpty().filter { hash(it) == h }.forEach { it.delete() }
        shots.shots.value.filter { hash(File(shots.dir, it.fileName)) == h }.forEach { shots.delete(it) }
        item.file.delete()
        refresh()
    }

    suspend fun bytes(item: MediaItem): ByteArray? = withContext(Dispatchers.IO) { item.file.takeIf { it.exists() }?.readBytes() }

    suspend fun thumbnail(item: MediaItem, side: Int = THUMB_SIDE): Bitmap? = withContext(Dispatchers.IO) {
        if (!item.file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.file.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        BitmapFactory.decodeFile(item.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Копия в галерею телефона (Pictures/Sputnik); разрешений не нужно — свой файл через MediaStore. */
    suspend fun saveToGallery(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val data = item.file.takeIf { it.exists() }?.readBytes() ?: return@withContext false
        val name = "sputnik_" + NAME.format(item.takenAt.atZone(ZoneId.systemDefault())) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sputnik")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        runCatching {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(data) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { Log.w(TAG, "save to gallery failed: ${it.javaClass.simpleName}") }.isSuccess
    }

    private val hashes = mutableMapOf<String, String>()

    /** MD5 содержимого — запоминается по пути и размеру файла. */
    private fun hash(file: File): String = synchronized(hashes) {
        val key = "${file.path}:${file.length()}"
        hashes.getOrPut(key) {
            val digest = MessageDigest.getInstance("MD5")
            runCatching { file.inputStream().use { input -> digest.update(input.readBytes()) } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private companion object {
        const val TAG = "Media"
        const val THUMB_SIDE = 240
        val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
