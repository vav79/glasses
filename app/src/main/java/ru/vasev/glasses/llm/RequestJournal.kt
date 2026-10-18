package ru.vasev.glasses.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Одна попытка запроса к модели (как строка лога `LLM`). [question] и [answer] — только если в настройках
 * включено «Сохранять тексты»; по умолчанию журнал хранит лишь метаданные.
 */
data class JournalEntry(
    val time: Instant,
    val role: Role,
    val provider: ProviderId,
    val model: String,
    /** ok, refused или error(kind status). */
    val status: String,
    val latencyMs: Long,
    val usage: Usage,
    /** sent, placeholder или none. */
    val photo: String,
    val fallback: Boolean,
    val costUsd: Double?,
    val question: String? = null,
    val answer: String? = null,
    /** Конец фразы пользователя → первый звук ответа; только для озвученных ответов ассистента. */
    val firstSoundMs: Long? = null,
    /** Ответ перебили (кнопкой, гарнитурой или голосом): счёт токенов может быть неполным. */
    val interrupted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time.toString())
        put("mode", role.name.lowercase())
        put("provider", provider.name.lowercase())
        put("model", model)
        put("status", status)
        put("latency_ms", latencyMs)
        put("in", usage.input)
        put("out", usage.output)
        put("cache_read", usage.cacheRead)
        put("cache_write", usage.cacheWrite)
        put("reasoning", usage.reasoning)
        put("photo", photo)
        put("fallback", fallback)
        costUsd?.let { put("cost_usd", it) }
        question?.let { put("question", it) }
        answer?.let { put("answer", it) }
        firstSoundMs?.let { put("first_sound_ms", it) }
        if (interrupted) put("interrupted", true)
    }

    companion object {
        fun fromJson(o: JSONObject): JournalEntry = JournalEntry(
            time = Instant.parse(o.getString("time")),
            role = Role.valueOf(o.getString("mode").uppercase()),
            provider = ProviderId.valueOf(o.getString("provider").uppercase()),
            model = o.getString("model"),
            status = o.getString("status"),
            latencyMs = o.getLong("latency_ms"),
            usage = Usage(
                input = o.optLong("in"),
                output = o.optLong("out"),
                cacheRead = o.optLong("cache_read"),
                cacheWrite = o.optLong("cache_write"),
                reasoning = o.optLong("reasoning"),
            ),
            photo = o.optString("photo", "none"),
            fallback = o.optBoolean("fallback"),
            costUsd = if (o.has("cost_usd")) o.getDouble("cost_usd") else null,
            question = if (o.has("question")) o.getString("question") else null,
            answer = if (o.has("answer")) o.getString("answer") else null,
            firstSoundMs = if (o.has("first_sound_ms")) o.getLong("first_sound_ms") else null,
            interrupted = o.optBoolean("interrupted"),
        )
    }
}

/**
 * Журнал запросов к моделям: JSONL-файл в личной папке приложения, последние [MAX_ENTRIES] записей.
 * Наружу уходит только по «Поделиться» на вкладке «Разработка».
 */
class RequestJournal(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val exportDir = File(context.cacheDir, EXPORT_DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())

    /** Записи от старых к новым. */
    val entries: StateFlow<List<JournalEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            synchronized(lock) {
                val loaded = runCatching {
                    if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
                        runCatching { JournalEntry.fromJson(JSONObject(line)) }.getOrNull()
                    }
                }.getOrElse {
                    Log.w(TAG, "journal read failed: ${it.javaClass.simpleName}")
                    emptyList()
                }
                // Записи, добавленные до окончания загрузки, остаются в конце.
                _entries.value = (loaded + _entries.value).takeLast(MAX_ENTRIES)
            }
        }
    }

    fun add(entry: JournalEntry) {
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        scope.launch {
            synchronized(lock) {
                runCatching {
                    file.appendText(entry.toJson().toString() + "\n")
                    // Обрезка пачкой, чтобы не переписывать файл на каждый запрос.
                    if (_entries.value.size >= MAX_ENTRIES) {
                        val lines = file.readLines()
                        if (lines.size > MAX_ENTRIES + TRIM_SLACK) file.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
                    }
                }.onFailure { Log.w(TAG, "journal write failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        scope.launch { synchronized(lock) { file.delete() } }
    }

    /** Копия журнала для «Поделиться» (в кэше, FileProvider отдаёт только эту папку). */
    fun exportCopy(): File = synchronized(lock) {
        exportDir.mkdirs()
        exportDir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        File(exportDir, "requests-$stamp.jsonl").also { out ->
            out.writeText(_entries.value.joinToString("") { it.toJson().toString() + "\n" })
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000
        private const val TRIM_SLACK = 100
        private const val FILE_NAME = "requests.jsonl"
        const val EXPORT_DIR = "export"
        private const val TAG = "LLM"
    }
}
