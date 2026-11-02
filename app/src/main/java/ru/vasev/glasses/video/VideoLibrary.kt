package ru.vasev.glasses.video

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant

/** Ролик в галерее. */
data class VideoItem(val uri: Uri, val name: String, val addedAt: Instant, val durationMs: Long, val sizeBytes: Long)

/**
 * Записанные ролики — из галереи (`Movies/Sputnik`). Свои файлы MediaStore отдаёт без разрешений; чужие
 * (скопированные туда вручную) не видны — это нормально.
 */
class VideoLibrary(context: Context) {

    private val resolver = context.contentResolver
    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    suspend fun refresh() {
        _items.value = withContext(Dispatchers.IO) { query() }
    }

    private fun query(): List<VideoItem> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        val out = mutableListOf<VideoItem>()
        runCatching {
            resolver.query(collection, columns, selection, arrayOf("${Mp4Segment.RELATIVE_PATH}%"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    out += VideoItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1).orEmpty(),
                        addedAt = Instant.ofEpochSecond(c.getLong(2)),
                        durationMs = c.getLong(3),
                        sizeBytes = c.getLong(4),
                    )
                }
            }
        }
        return out
    }

    /** Удалить ролик из галереи (свой — без разрешений). */
    suspend fun delete(item: VideoItem) {
        withContext(Dispatchers.IO) { runCatching { resolver.delete(item.uri, null, null) } }
        refresh()
    }
}
