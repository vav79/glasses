package ru.vasev.glasses.camera

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Снимок из ленты «Камеры»: файл в папке приложения, когда и откуда. */
data class Shot(val id: String, val takenAt: Instant, val source: ImageSource) {
    val fileName: String get() = "$id.${source.name.lowercase()}.jpg"
}

/**
 * Лента последних снимков — только на телефоне (`files/shots`, резервное копирование отключено), не больше
 * [MAX_SHOTS]: старые удаляются сами. Каждый снимок уже прошёл [ImagePrep] — без EXIF и GPS.
 * В галерею телефона снимок попадает только по явному «Сохранить в галерею».
 */
class ShotStore(context: Context) {

    /** Папка ленты — её читает и «Медиа». */
    val dir = File(context.filesDir, DIR)
    private val resolver = context.contentResolver
    private val lock = Any()

    private val _shots = MutableStateFlow(load())

    /** От новых к старым. */
    val shots: StateFlow<List<Shot>> = _shots.asStateFlow()

    suspend fun add(jpeg: ByteArray, source: ImageSource): Shot = withContext(Dispatchers.IO) {
        val shot = Shot(System.currentTimeMillis().toString(), Instant.now(), source)
        synchronized(lock) {
            dir.mkdirs()
            File(dir, shot.fileName).writeBytes(jpeg)
            _shots.update { listOf(shot) + it }
            _shots.value.drop(MAX_SHOTS).forEach { File(dir, it.fileName).delete() }
            _shots.update { it.take(MAX_SHOTS) }
        }
        shot
    }

    suspend fun delete(shot: Shot) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            File(dir, shot.fileName).delete()
            _shots.update { list -> list.filterNot { it.id == shot.id } }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            _shots.value = emptyList()
        }
    }

    suspend fun jpeg(shot: Shot): ByteArray? = withContext(Dispatchers.IO) {
        File(dir, shot.fileName).takeIf { it.exists() }?.readBytes()
    }

    /** Уменьшенная копия для плитки ленты. */
    suspend fun thumbnail(shot: Shot, side: Int = THUMB_SIDE): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(dir, shot.fileName)
        if (!f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Копия в галерею телефона (Pictures/Glasses); разрешений не нужно — свой файл через MediaStore. */
    suspend fun saveToGallery(shot: Shot): Boolean = withContext(Dispatchers.IO) {
        val bytes = jpeg(shot) ?: return@withContext false
        val name = "glasses_" + NAME.format(shot.takenAt.atZone(ZoneId.systemDefault())) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Glasses")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        runCatching {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { Log.w(TAG, "save to gallery failed: ${it.javaClass.simpleName}") }.isSuccess
    }

    private fun load(): List<Shot> = runCatching {
        dir.listFiles().orEmpty().mapNotNull { file ->
            val parts = file.name.removeSuffix(".jpg").split('.')
            val millis = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val source = ImageSource.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(1) } ?: return@mapNotNull null
            Shot(parts[0], Instant.ofEpochMilli(millis), source)
        }.sortedByDescending { it.takenAt }.take(MAX_SHOTS)
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_SHOTS = 30
        private const val THUMB_SIDE = 160
        private const val DIR = "shots"
        private const val TAG = "Image"
        private val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
