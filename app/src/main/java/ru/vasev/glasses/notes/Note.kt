package ru.vasev.glasses.notes

import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant

/** Заметка «Запомни, …»: текст, время, координаты (если были) и снимок (лежит рядом файлом, если был). */
data class Note(
    val id: String,
    val text: String,
    val createdAt: Instant,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Точность координат в метрах. */
    val accuracyM: Float? = null,
    val hasPhoto: Boolean = false,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("created_at", createdAt.toString())
        latitude?.let { put("lat", it) }
        longitude?.let { put("lon", it) }
        accuracyM?.let { put("accuracy_m", it.toDouble()) }
        put("photo", hasPhoto)
    }

    companion object {
        fun fromJson(o: JSONObject) = Note(
            id = o.getString("id"),
            text = o.getString("text"),
            createdAt = Instant.parse(o.getString("created_at")),
            latitude = if (o.has("lat")) o.getDouble("lat") else null,
            longitude = if (o.has("lon")) o.getDouble("lon") else null,
            accuracyM = if (o.has("accuracy_m")) o.getDouble("accuracy_m").toFloat() else null,
            hasPhoto = o.optBoolean("photo"),
        )
    }
}

/**
 * Поиск заметок по словам запроса без учёта окончаний: «машину», «машина», «машины» — одно слово.
 * Основа слова — без двух последних букв (но не короче трёх); слова совпадают, если одна основа начинается
 * с другой. Чистая логика, покрыта тестами. Синонимы («авто» / «машина») подбирает модель: она может искать снова.
 */
object NoteSearch {

    /** Заметки с хотя бы одним совпавшим словом: больше совпадений — выше, при равенстве — новее. Пустой запрос — последние. */
    fun search(notes: List<Note>, query: String, limit: Int): List<Note> {
        val wanted = stems(query)
        if (wanted.isEmpty()) return notes.sortedByDescending { it.createdAt }.take(limit)
        return notes
            .map { note -> note to score(wanted, stems(note.text)) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit)
            .map { it.first }
    }

    private fun score(wanted: Set<String>, have: Set<String>): Int =
        wanted.count { w -> have.any { h -> h.startsWith(w) || w.startsWith(h) } }

    /** Основы слов длиной от трёх букв; короткие слова (предлоги, «я», «у») не участвуют. */
    fun stems(text: String): Set<String> =
        normalize(text).split(' ').filter { it.length >= MIN_WORD }.map(::stem).toSet()

    fun stem(word: String): String = if (word.length <= MIN_WORD) word else word.take(maxOf(MIN_WORD, word.length - 2))

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private const val MIN_WORD = 3
}
