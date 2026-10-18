package ru.vasev.glasses.rates

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Курсы и откуда они: [fetchedAt] — когда скачаны, [offline] — сети нет, взят последний сохранённый курс. */
data class Rates(val table: RateTable, val source: String, val fetchedAt: Instant, val offline: Boolean)

/**
 * Курсы ЦБ РФ с кешем на день. Источник — cbr.ru; из-за границы он бывает недоступен — тогда зеркало
 * cbr-xml-daily.ru с теми же данными. Кеш обновляется, пока есть сеть (при появлении сети и при запросе);
 * без сети — последний сохранённый курс с его датой.
 */
class CbrRates(context: Context, private val online: () -> Boolean) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _cached = MutableStateFlow(load())

    /** Последние сохранённые курсы (для настроек: «курс ЦБ на …»). */
    val cached: StateFlow<Rates?> = _cached.asStateFlow()

    /** Курсы для пересчёта: свежие (не старше дня) из кеша, иначе скачать; не вышло — последние сохранённые. null — курсов нет. */
    suspend fun get(): Rates? = mutex.withLock {
        val cached = _cached.value
        if (cached != null && isFresh(cached)) return cached
        if (online()) fetch()?.let { return it }
        cached?.copy(offline = !online())
    }

    /** Фоновое обновление кеша, когда появилась сеть. */
    suspend fun refreshIfStale() {
        mutex.withLock {
            val cached = _cached.value
            if ((cached == null || !isFresh(cached)) && online()) fetch()
        }
    }

    private fun isFresh(r: Rates): Boolean =
        r.fetchedAt.atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

    private suspend fun fetch(): Rates? = withContext(Dispatchers.IO) {
        for ((url, name) in SOURCES) {
            val table = runCatching { download(url) }
                .onFailure { Log.w(TAG, "rates from $name failed: ${it.javaClass.simpleName}") }
                .getOrNull() ?: continue
            val rates = Rates(table, name, Instant.now(), offline = false)
            Log.d(TAG, "rates source=$name date=${table.date} currencies=${table.rubPerUnit.size}")
            runCatching { save(rates) }.onFailure { Log.w(TAG, "rates cache write failed: ${it.javaClass.simpleName}") }
            _cached.value = rates
            return@withContext rates
        }
        null
    }

    private fun download(url: String): RateTable {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val bytes = response.body!!.bytes()
            // cbr.ru отдаёт windows-1251 (кириллица в названиях валют), зеркало — UTF-8; кодировка — из XML-декларации.
            val head = String(bytes, 0, minOf(bytes.size, 100), Charsets.US_ASCII)
            val charset = if (head.contains("windows-1251", ignoreCase = true)) Charset.forName("windows-1251") else Charsets.UTF_8
            return RateTable.parseCbrXml(String(bytes, charset))
        }
    }

    private fun load(): Rates? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        Rates(RateTable.fromJson(o.getJSONObject("table")), o.getString("source"), Instant.parse(o.getString("fetched_at")), offline = false)
    }.getOrNull()

    private fun save(r: Rates) {
        file.writeText(JSONObject().put("table", r.table.toJson()).put("source", r.source).put("fetched_at", r.fetchedAt.toString()).toString())
    }

    private companion object {
        const val TAG = "Rates"
        const val CACHE_FILE = "cbr_rates.json"
        val SOURCES = listOf(
            "https://www.cbr.ru/scripts/XML_daily.asp" to "cbr.ru",
            "https://www.cbr-xml-daily.ru/daily_utf8.xml" to "cbr-xml-daily.ru",
        )
    }
}
