package ru.vasev.glasses.chats

import org.json.JSONArray
import org.json.JSONObject
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.WebSource

/** Чат в списке: [updated] — время последнего обмена (мс, часы телефона). */
data class ChatMeta(
    val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    /** Начат с очков: жестом на дужке, «Hey Meta» или микрофоном очков. */
    val fromGlasses: Boolean = false,
    /** Ответ пришёл, пока чат не был на экране. */
    val unread: Boolean = false,
    /** Текст вопросов и ответов в нижнем регистре — для поиска без чтения файлов. */
    val searchText: String = "",
)

/** Заметка под ответом: [saved] — сохранена в этом ответе, иначе найдена. */
data class NoteRef(val id: String, val saved: Boolean)

/** Ссылка или QR-код со снимка — исходный текст, проверка ([ru.vasev.glasses.links.LinkCheck]) повторяется при загрузке. */
data class LinkRef(val raw: String, val fromQr: Boolean)

/** Сохранённый обмен чата: всё, что видно под ответом, и вопрос, как его получила модель. */
data class StoredExchange(
    /** Вопрос, как его получила модель (с пометкой о ссылках на снимке). */
    val asked: String,
    /** Вопрос, как он показан в чате; null — так же, как [asked]. */
    val shown: String? = null,
    val summary: String,
    val details: String = "",
    val model: ModelTag? = null,
    /** Источник снимка ([ru.vasev.glasses.camera.ImageSource.name]). */
    val source: String? = null,
    /** Файл снимка в папке медиа чатов ([ChatStore.photoFile]). */
    val photo: String? = null,
    val interrupted: Boolean = false,
    val silent: Boolean = false,
    val translation: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    val notes: List<NoteRef> = emptyList(),
    val deletedNotes: List<String> = emptyList(),
    /** Дата курса ЦБ (ISO) и «сохранённый, без сети». */
    val ratesDate: String? = null,
    val ratesOffline: Boolean = false,
    val links: List<LinkRef> = emptyList(),
    /** Не обмен, а разделитель в ленте («— Профиль: Инженер —»); в память модели не идёт. */
    val divider: String? = null,
    /** Реплика «Переводчика»: чья ([ru.vasev.glasses.translate.Who.name]); [asked] — сказанное, [summary] — перевод. В память модели не идёт. */
    val lineWho: String? = null,
) {
    /** Ответ целиком — как его помнит модель. */
    val answer: String get() = listOf(summary, details).filter { it.isNotEmpty() }.joinToString("\n\n")
}

/** JSON чатов (org.json; тесты — ChatRecordsTest). */
object ChatJson {

    fun meta(m: ChatMeta): JSONObject = JSONObject()
        .put("id", m.id)
        .put("title", m.title)
        .put("created", m.created)
        .put("updated", m.updated)
        .put("from_glasses", m.fromGlasses)
        .put("unread", m.unread)
        .put("search", m.searchText)

    fun meta(o: JSONObject): ChatMeta = ChatMeta(
        id = o.getString("id"),
        title = o.optString("title"),
        created = o.optLong("created"),
        updated = o.optLong("updated"),
        fromGlasses = o.optBoolean("from_glasses"),
        unread = o.optBoolean("unread"),
        searchText = o.optString("search"),
    )

    fun exchange(e: StoredExchange): JSONObject = JSONObject().apply {
        put("asked", e.asked)
        e.shown?.let { put("shown", it) }
        put("summary", e.summary)
        if (e.details.isNotEmpty()) put("details", e.details)
        e.model?.let {
            put(
                "model",
                JSONObject().put("title", it.title).put("fallback", it.viaFallback).put("auto_vision", it.autoVision)
                    .put("no_web_search", it.noWebSearch),
            )
        }
        e.source?.let { put("source", it) }
        e.photo?.let { put("photo", it) }
        if (e.interrupted) put("interrupted", true)
        if (e.silent) put("silent", true)
        if (e.translation) put("translation", true)
        if (e.sources.isNotEmpty()) put("sources", JSONArray(e.sources.map { JSONObject().put("url", it.url).put("title", it.title) }))
        if (e.notes.isNotEmpty()) put("notes", JSONArray(e.notes.map { JSONObject().put("id", it.id).put("saved", it.saved) }))
        if (e.deletedNotes.isNotEmpty()) put("deleted_notes", JSONArray(e.deletedNotes))
        e.ratesDate?.let { put("rates_date", it).put("rates_offline", e.ratesOffline) }
        if (e.links.isNotEmpty()) put("links", JSONArray(e.links.map { JSONObject().put("raw", it.raw).put("qr", it.fromQr) }))
        e.divider?.let { put("divider", it) }
        e.lineWho?.let { put("line_who", it) }
    }

    fun exchange(o: JSONObject): StoredExchange = StoredExchange(
        asked = o.getString("asked"),
        shown = o.optStringOrNull("shown"),
        summary = o.optString("summary"),
        details = o.optString("details"),
        model = o.optJSONObject("model")?.let {
            ModelTag(it.optString("title"), it.optBoolean("fallback"), it.optBoolean("auto_vision"), it.optBoolean("no_web_search"))
        },
        source = o.optStringOrNull("source"),
        photo = o.optStringOrNull("photo"),
        interrupted = o.optBoolean("interrupted"),
        silent = o.optBoolean("silent"),
        translation = o.optBoolean("translation"),
        sources = o.optJSONArray("sources").objects().map { WebSource(it.optString("url"), it.optString("title")) },
        notes = o.optJSONArray("notes").objects().map { NoteRef(it.optString("id"), it.optBoolean("saved")) },
        deletedNotes = o.optJSONArray("deleted_notes").strings(),
        ratesDate = o.optStringOrNull("rates_date"),
        ratesOffline = o.optBoolean("rates_offline"),
        links = o.optJSONArray("links").objects().map { LinkRef(it.optString("raw"), it.optBoolean("qr")) },
        divider = o.optStringOrNull("divider"),
        lineWho = o.optStringOrNull("line_who"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

/** Правила списка чатов (чистая логика, тесты — ChatRecordsTest). */
object ChatRules {

    /** После такой тишины следующий вопрос начинает новый чат (решение В10). */
    const val NEW_CHAT_AFTER_MS = 2 * 60 * 60 * 1000L

    /** Сколько чатов в боковом меню; остальные — «Показать все». */
    const val RECENT = 7

    private const val TITLE_MAX = 40
    private const val SEARCH_MAX = 20_000

    fun expired(updated: Long, now: Long): Boolean = now - updated >= NEW_CHAT_AFTER_MS

    /** Название — из первого вопроса, без запроса к модели: первая строка, не длиннее [TITLE_MAX] символов. */
    fun title(question: String): String {
        val line = question.trim().lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= TITLE_MAX) line else line.take(TITLE_MAX).trimEnd() + "…"
    }

    /** Текст для поиска: дописывается каждый обмен, хвост обрезается. */
    fun appendSearch(current: String, question: String, answer: String): String =
        "$current\n${question.lowercase()}\n${answer.lowercase()}".trim().take(SEARCH_MAX)

    /** Поиск по названию и тексту, без учёта регистра; пустой запрос — все. Порядок сохраняется. */
    fun search(chats: List<ChatMeta>, query: String): List<ChatMeta> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return chats
        return chats.filter { q in it.title.lowercase() || q in it.searchText }
    }
}
