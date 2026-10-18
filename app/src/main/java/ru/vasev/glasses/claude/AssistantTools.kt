package ru.vasev.glasses.claude

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.llm.ToolCall
import ru.vasev.glasses.llm.ToolOutput
import ru.vasev.glasses.llm.ToolSet
import ru.vasev.glasses.llm.ToolSpec
import ru.vasev.glasses.llm.stringOrNull
import ru.vasev.glasses.notes.Note
import ru.vasev.glasses.notes.NoteStore
import ru.vasev.glasses.rates.CbrRates
import ru.vasev.glasses.rates.Rates
import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Режим ассистента: обычный разговор или «Переведи, что вижу». */
enum class AssistantMode { CHAT, TRANSLATE_VIEW }

/**
 * Что модель получает в запросе. Защита от чужих команд: текст на снимках и страницы из поиска могут содержать
 * инструкции для модели, поэтому удаление заметок даётся, только если в МОЕЙ фразе есть явная просьба удалить
 * или забыть, и никогда — вместе с веб-поиском и в «Переведи, что вижу».
 */
data class ToolPlan(val notes: Boolean, val deleteNotes: Boolean, val rates: Boolean, val webSearch: Boolean) {
    companion object {
        fun of(mode: AssistantMode, phrase: String, deleteTriggers: Collection<String>): ToolPlan = when (mode) {
            AssistantMode.TRANSLATE_VIEW -> ToolPlan(notes = false, deleteNotes = false, rates = true, webSearch = false)
            AssistantMode.CHAT -> {
                val delete = asksToDelete(phrase, deleteTriggers)
                ToolPlan(notes = true, deleteNotes = delete, rates = true, webSearch = !delete)
            }
        }

        /** Есть ли в фразе слово, начинающееся с одной из [triggers] («удали», «забудь», «delete», «forget» …). */
        fun asksToDelete(phrase: String, triggers: Collection<String>): Boolean {
            val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
            val stems = triggers.map(::normalize).filter { it.isNotEmpty() }
            return words.any { word -> stems.any { word.startsWith(it) } }
        }

        /** Начинается ли фраза с «запомни» / «remember» … — тогда снимок и координаты для заметки готовятся заранее. */
        fun startsWithAny(phrase: String, triggers: Collection<String>): Boolean {
            val text = normalize(phrase)
            return triggers.map(::normalize).any { it.isNotEmpty() && (text == it || text.startsWith("$it ")) }
        }

        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}

/**
 * Инструменты одного ответа ассистента: заметки (сохранить / найти / удалить) и пересчёт цен по курсу ЦБ.
 * Заметки и снимки остаются на телефоне — модель получает только текст и время найденных заметок
 * (без координат и фото). Что сохранено, найдено и удалено — для карточек в чате.
 */
class AssistantTools(
    private val plan: ToolPlan,
    private val notes: NoteStore,
    private val rates: CbrRates,
    /** «Моя валюта». */
    private val myCurrency: String,
    /** Снимок для заметки (уже снятый или снять сейчас); null — источника нет или кадр почти чёрный. */
    private val notePhoto: suspend () -> ByteArray?,
    /** Координаты для заметки; null — нет разрешения или фиксации. */
    private val noteLocation: suspend () -> DeviceLocation.Fix?,
) {
    val saved = mutableListOf<Note>()
    val found = mutableListOf<Note>()
    val deleted = mutableListOf<Note>()

    /** Курсы, по которым пересчитывали в этом ответе (для подписи «курс ЦБ на …»). */
    var ratesUsed: Rates? = null
        private set

    fun toolSet(): ToolSet? {
        val specs = buildList {
            if (plan.notes) {
                add(SAVE_NOTE)
                add(FIND_NOTES)
            }
            if (plan.deleteNotes) add(DELETE_NOTES)
            if (plan.rates) add(convertPrices(myCurrency))
        }
        return if (specs.isEmpty()) null else ToolSet(specs, ::execute)
    }

    private suspend fun execute(call: ToolCall): ToolOutput = try {
        Log.d(TAG, "tool=${call.name}")
        when (call.name) {
            SAVE_NOTE.name -> if (plan.notes) save(call.input) else unavailable(call)
            FIND_NOTES.name -> if (plan.notes) find(call.input) else unavailable(call)
            DELETE_NOTES.name -> if (plan.deleteNotes) delete(call.input) else unavailable(call)
            CONVERT_PRICES -> if (plan.rates) convert(call.input) else unavailable(call)
            else -> unavailable(call)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "tool ${call.name} failed: ${e.javaClass.simpleName}")
        ToolOutput("tool failed", isError = true)
    }

    private fun unavailable(call: ToolCall) = ToolOutput("tool ${call.name} is not available", isError = true)

    private suspend fun save(input: JSONObject): ToolOutput {
        val text = input.stringOrNull("text") ?: return ToolOutput("text is required", isError = true)
        // Повтор той же заметки (модель пересохраняет просьбу из прошлого хода) — не дублируем.
        notes.recentDuplicate(text, DUPLICATE_WINDOW_MS)?.let { existing ->
            return ToolOutput(JSONObject().put("saved", true).put("id", existing.id).put("already_saved", true).toString())
        }
        // Снимок и координаты обычно уже готовы (их начали получать, как только прозвучало «запомни»); ждём недолго.
        val photo = withTimeoutOrNull(EXTRA_TIMEOUT_MS) { notePhoto() }
        val location = withTimeoutOrNull(EXTRA_TIMEOUT_MS) { noteLocation() }
        val note = notes.add(text.take(MAX_NOTE_LENGTH), photo, location)
        saved += note
        Log.d(TAG, "note saved photo=${photo != null} location=${location != null}")
        return ToolOutput(
            JSONObject().put("saved", true).put("id", note.id)
                .put("photo_attached", note.hasPhoto).put("location_attached", note.hasLocation).toString(),
        )
    }

    private fun find(input: JSONObject): ToolOutput {
        val result = notes.search(input.stringOrNull("query").orEmpty())
        result.forEach { note -> if (found.none { it.id == note.id }) found += note }
        val array = JSONArray()
        result.forEach { array.put(noteForModel(it)) }
        return ToolOutput(JSONObject().put("notes", array).put("count", result.size).toString())
    }

    private suspend fun delete(input: JSONObject): ToolOutput {
        val ids = input.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (ids.isEmpty()) return ToolOutput("ids are required: find the notes with find_notes first", isError = true)
        val removed = notes.delete(ids)
        deleted += removed
        found.removeAll { note -> removed.any { it.id == note.id } }
        return ToolOutput(JSONObject().put("deleted", JSONArray(removed.map { it.text })).put("count", removed.size).toString())
    }

    private suspend fun convert(input: JSONObject): ToolOutput {
        val from = input.stringOrNull("currency")?.uppercase() ?: return ToolOutput("currency is required", isError = true)
        val amounts = input.optJSONArray("amounts")?.let { a -> (0 until a.length()).mapNotNull { a.optDouble(it).takeUnless(Double::isNaN) } }.orEmpty()
        val r = rates.get() ?: return ToolOutput("exchange rates are unavailable: no internet and no saved rates", isError = true)
        val rate = r.table.rate(from, myCurrency)
            ?: return ToolOutput("no Bank of Russia rate for $from", isError = true)
        ratesUsed = r
        val results = JSONArray()
        amounts.forEach { amount -> results.put(JSONObject().put("amount", amount).put("converted", round(amount * rate))) }
        return ToolOutput(
            JSONObject()
                .put("from", from)
                .put("to", myCurrency)
                .put("rate", "1 $from = ${round(rate)} $myCurrency")
                .put("rates_date", r.table.date.toString())
                .put("source", "Bank of Russia official rates via ${r.source}")
                .put("offline_cached_rates", r.offline)
                .put("results", results)
                .toString(),
        )
    }

    private fun round(value: Double): Double = if (value >= 100) value.roundToLong().toDouble() else (value * 100).roundToLong() / 100.0

    private fun noteForModel(note: Note): JSONObject = JSONObject()
        .put("id", note.id)
        .put("text", note.text)
        .put("saved_at", SAVED_AT.format(note.createdAt.atZone(ZoneId.systemDefault())))
        .put("has_photo", note.hasPhoto)
        .put("has_location", note.hasLocation)

    companion object {
        private const val TAG = "Tools"
        private const val EXTRA_TIMEOUT_MS = 10_000L
        private const val MAX_NOTE_LENGTH = 500
        private const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
        private val SAVED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE", Locale.US)

        val SAVE_NOTE = ToolSpec(
            name = "save_note",
            description = "Save a note on the user's phone when the user explicitly asks to remember something " +
                "(\"remember, I parked on level 2\"). The app attaches the current time, the phone's location and a photo " +
                "from the selected camera automatically. Confirm briefly after saving.",
            properties = mapOf(
                "text" to mapOf(
                    "type" to "string",
                    "description" to "The note in the user's language, self-contained, e.g. \"Car parked on level -2, spot 45\".",
                ),
            ),
            required = listOf("text"),
        )

        val FIND_NOTES = ToolSpec(
            name = "find_notes",
            description = "Search the notes the user asked to remember earlier, e.g. for \"where is my car?\" or " +
                "\"what did I note about the hotel?\". Returns the text and time of matching notes; their photos and the " +
                "route button are shown on the phone screen automatically. If nothing is found, try once more with synonyms.",
            properties = mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "A few keywords in the language of the notes; an empty string lists the latest notes.",
                ),
            ),
            required = listOf("query"),
        )

        val DELETE_NOTES = ToolSpec(
            name = "delete_notes",
            description = "Delete notes by id. Use only because the user explicitly asked in their message to delete or " +
                "forget something; find the ids with find_notes first and delete only the notes that match.",
            properties = mapOf(
                "ids" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Ids from find_notes."),
            ),
            required = listOf("ids"),
        )

        const val CONVERT_PRICES = "convert_prices"

        fun convertPrices(myCurrency: String) = ToolSpec(
            name = CONVERT_PRICES,
            description = "Convert amounts of money into the user's currency ($myCurrency) at the official Bank of Russia " +
                "rates. Call it once with all amounts in the same currency.",
            properties = mapOf(
                "currency" to mapOf("type" to "string", "description" to "ISO 4217 code of the amounts, e.g. EUR, TRY, CNY."),
                "amounts" to mapOf("type" to "array", "items" to mapOf("type" to "number"), "description" to "Amounts to convert."),
            ),
            required = listOf("currency", "amounts"),
        )
    }
}
