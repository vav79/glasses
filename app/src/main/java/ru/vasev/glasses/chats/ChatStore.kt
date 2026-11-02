package ru.vasev.glasses.chats

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * История чатов — только на телефоне: `files/chats/index.json` (список, свежие первыми) и
 * `files/chats/<id>.json` (обмены). Снимки — отдельно, в `files/media/chat/`: при удалении чата
 * они остаются в «Медиа» (решение В10).
 */
class ChatStore(context: Context) {

    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val mediaDir = File(context.filesDir, "media/chat").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()

    private val _chats = MutableStateFlow(readIndex())

    /** Все чаты, последний обмен — первым. */
    val chats: StateFlow<List<ChatMeta>> = _chats.asStateFlow()

    fun photoFile(name: String): File = File(mediaDir, name)

    /** Снимок обмена — в папку медиа чатов; возвращает имя файла. */
    suspend fun savePhoto(jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val name = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        File(mediaDir, name).writeBytes(jpeg)
        name
    }

    /** Новый чат с первым обменом; возвращает его id. */
    suspend fun create(first: StoredExchange, fromGlasses: Boolean, unread: Boolean): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = "$now-${UUID.randomUUID().toString().take(6)}"
            val meta = ChatMeta(
                id = id,
                title = ChatRules.title(first.shown ?: first.asked),
                created = now,
                updated = now,
                fromGlasses = fromGlasses,
                unread = unread,
                searchText = ChatRules.appendSearch("", first.shown ?: first.asked, first.answer),
            )
            writeExchanges(id, listOf(first))
            saveIndex(listOf(meta) + _chats.value)
            id
        }
    }

    /** Дописать обмен в чат и поднять его наверх списка. */
    suspend fun append(id: String, exchange: StoredExchange, unread: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val meta = _chats.value.firstOrNull { it.id == id } ?: return@withContext
            writeExchanges(id, readExchanges(id) + exchange)
            val updated = meta.copy(
                updated = System.currentTimeMillis(),
                unread = meta.unread || unread,
                searchText = ChatRules.appendSearch(meta.searchText, exchange.shown ?: exchange.asked, exchange.answer),
            )
            saveIndex(listOf(updated) + _chats.value.filter { it.id != id })
        }
    }

    suspend fun load(id: String): List<StoredExchange> = mutex.withLock { withContext(Dispatchers.IO) { readExchanges(id) } }

    suspend fun markRead(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (_chats.value.none { it.id == id && it.unread }) return@withContext
            saveIndex(_chats.value.map { if (it.id == id) it.copy(unread = false) else it })
        }
    }

    /** Удалить переписку чата. Снимки остаются в «Медиа». */
    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            File(dir, "$id.json").delete()
            saveIndex(_chats.value.filter { it.id != id })
            Log.d(TAG, "chat deleted: $id")
        }
    }

    private fun readIndex(): List<ChatMeta> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val array = JSONArray(indexFile.readText())
        (0 until array.length()).map { ChatJson.meta(array.getJSONObject(it)) }.sortedByDescending { it.updated }
    }.onFailure { Log.w(TAG, "chat index unreadable: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    private fun saveIndex(chats: List<ChatMeta>) {
        val sorted = chats.sortedByDescending { it.updated }
        writeAtomically(indexFile, JSONArray(sorted.map(ChatJson::meta)).toString())
        _chats.value = sorted
    }

    private fun readExchanges(id: String): List<StoredExchange> = runCatching {
        val file = File(dir, "$id.json")
        if (!file.exists()) return emptyList()
        val array = JSONObject(file.readText()).getJSONArray("exchanges")
        (0 until array.length()).map { ChatJson.exchange(array.getJSONObject(it)) }
    }.onFailure { Log.w(TAG, "chat $id unreadable: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    private fun writeExchanges(id: String, exchanges: List<StoredExchange>) {
        writeAtomically(File(dir, "$id.json"), JSONObject().put("exchanges", JSONArray(exchanges.map(ChatJson::exchange))).toString())
    }

    /** Через временный файл: оборванная запись не портит чат. */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val TAG = "Chats"
    }
}
