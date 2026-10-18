package ru.vasev.glasses.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.time.Instant
import ru.vasev.glasses.location.DeviceLocation
import java.util.UUID

/**
 * Заметки «Запомни» — только на телефоне: `files/notes/notes.json` и снимки `files/notes/<id>.jpg`
 * (резервное копирование приложения отключено). В модель уходит лишь текст найденных заметок.
 */
class NoteStore(context: Context) {

    private val dir = File(context.filesDir, DIR)
    private val file = File(dir, FILE_NAME)
    private val lock = Any()

    private val _notes = MutableStateFlow(load())

    /** От новых к старым. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    suspend fun add(text: String, jpeg: ByteArray?, location: DeviceLocation.Fix?): Note = withContext(Dispatchers.IO) {
        val note = Note(
            id = UUID.randomUUID().toString().take(8),
            text = text,
            createdAt = Instant.now(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyM = location?.accuracyM,
            hasPhoto = jpeg != null,
        )
        synchronized(lock) {
            dir.mkdirs()
            jpeg?.let { photoFile(note.id).writeBytes(it) }
            _notes.update { listOf(note) + it }
            save()
        }
        note
    }

    /** Удаляет заметки и их снимки; возвращает удалённые. */
    suspend fun delete(ids: Collection<String>): List<Note> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val removed = _notes.value.filter { it.id in ids }
            if (removed.isEmpty()) return@withContext removed
            removed.forEach { photoFile(it.id).delete() }
            _notes.update { list -> list.filterNot { it.id in ids } }
            save()
            removed
        }
    }

    fun search(query: String, limit: Int = SEARCH_LIMIT): List<Note> = NoteSearch.search(_notes.value, query, limit)

    /** Такая же заметка (без учёта регистра и знаков), сохранённая не раньше [windowMs] назад. */
    fun recentDuplicate(text: String, windowMs: Long): Note? {
        val key = NoteSearch.stems(text)
        val since = Instant.now().minusMillis(windowMs)
        return _notes.value.firstOrNull { it.createdAt.isAfter(since) && NoteSearch.stems(it.text) == key }
    }

    fun byId(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    /** Снимок заметки, уменьшенный до [maxSide] по длинной стороне; null — снимка нет. */
    suspend fun photo(note: Note, maxSide: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        val f = photoFile(note.id)
        if (!note.hasPhoto || !f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun photoFile(id: String) = File(dir, "$id.jpg")

    private fun load(): List<Note> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { runCatching { Note.fromJson(array.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }.getOrElse {
        Log.w(TAG, "notes read failed: ${it.javaClass.simpleName}")
        emptyList()
    }

    /** Запись через временный файл: обрыв посреди записи не портит заметки. */
    private fun save() {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(JSONArray().apply { _notes.value.forEach { put(it.toJson()) } }.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val SEARCH_LIMIT = 5
        private const val DIR = "notes"
        private const val FILE_NAME = "notes.json"
        private const val TAG = "Notes"
    }
}
