package ru.vasev.glasses.rates

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Официальные курсы ЦБ РФ на дату [date]: сколько рублей за единицу валюты. Кросс-курс любых двух валют —
 * через рубль, как и у ЦБ. Чистая логика, покрыта тестами.
 */
data class RateTable(val date: LocalDate, val rubPerUnit: Map<String, Double>) {

    fun has(code: String): Boolean = code.uppercase() == RUB || code.uppercase() in rubPerUnit

    /** Сколько единиц [to] за одну [from]; null — валюты нет в таблице ЦБ. */
    fun rate(from: String, to: String): Double? {
        val a = rubPer(from) ?: return null
        val b = rubPer(to) ?: return null
        return a / b
    }

    fun convert(amount: Double, from: String, to: String): Double? = rate(from, to)?.let { amount * it }

    private fun rubPer(code: String): Double? = if (code.uppercase() == RUB) 1.0 else rubPerUnit[code.uppercase()]

    fun toJson(): JSONObject = JSONObject().put("date", date.toString()).put("rates", JSONObject(rubPerUnit))

    companion object {
        const val RUB = "RUB"

        fun fromJson(o: JSONObject): RateTable {
            val rates = o.getJSONObject("rates")
            return RateTable(LocalDate.parse(o.getString("date")), rates.keys().asSequence().associateWith { rates.getDouble(it) })
        }

        private val DATE = Regex("""<ValCurs[^>]*\sDate="(\d{2}\.\d{2}\.\d{4})"""")
        private val VALUTE = Regex("""<Valute\b.*?</Valute>""", RegexOption.DOT_MATCHES_ALL)
        private val CBR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /**
         * Разбор `XML_daily.asp` (cbr.ru, windows-1251) и его зеркала `daily_utf8.xml` (cbr-xml-daily.ru) — формат один.
         * Значения с запятой, курс — за [Nominal] единиц (100 иен, 10 юаней и т. п.). Бросает исключение на чужом ответе.
         */
        fun parseCbrXml(xml: String): RateTable {
            val date = DATE.find(xml)?.groupValues?.get(1) ?: error("no ValCurs date")
            val rates = VALUTE.findAll(xml).mapNotNull { match ->
                val block = match.value
                val code = tag(block, "CharCode") ?: return@mapNotNull null
                val nominal = tag(block, "Nominal")?.toDoubleOrNull() ?: return@mapNotNull null
                val value = tag(block, "Value")?.replace(',', '.')?.replace(" ", "")?.toDoubleOrNull() ?: return@mapNotNull null
                if (nominal <= 0 || value <= 0) null else code.uppercase() to value / nominal
            }.toMap()
            require(rates.isNotEmpty()) { "no rates" }
            return RateTable(LocalDate.parse(date, CBR_DATE), rates)
        }

        private fun tag(block: String, name: String): String? =
            Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(block)?.groupValues?.get(1)
    }
}
