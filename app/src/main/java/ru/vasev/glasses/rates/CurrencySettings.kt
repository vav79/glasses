package ru.vasev.glasses.rates

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Currency
import java.util.Locale

/** «Моя валюта» — в неё пересчитываются цены в «Переведи, что вижу». По умолчанию рубли. */
class CurrencySettings(context: Context) {

    private val prefs = context.getSharedPreferences("money", Context.MODE_PRIVATE)

    private val _currency = MutableStateFlow(prefs.getString(KEY, null)?.takeIf { it in CHOICES } ?: DEFAULT)
    val currency: StateFlow<String> = _currency.asStateFlow()

    fun set(code: String) {
        if (code !in CHOICES) return
        prefs.edit().putString(KEY, code).apply()
        _currency.value = code
    }

    companion object {
        const val DEFAULT = RateTable.RUB

        /** Валюты на выбор — все есть в таблице ЦБ. */
        val CHOICES = listOf("RUB", "USD", "EUR", "CNY", "GBP", "TRY", "AED", "KZT", "BYN", "GEL", "AMD", "THB", "JPY")

        /** «Евро (EUR)» на языке экрана. */
        fun title(code: String, locale: Locale): String =
            runCatching { Currency.getInstance(code).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) } }.getOrDefault(code) + " ($code)"

        /** Валюта страны (ISO 3166-1 alpha-2): «TR» → «TRY»; null — не определить. */
        fun forCountry(country: String): String? =
            runCatching { Currency.getInstance(Locale("", country.uppercase()))?.currencyCode }.getOrNull()

        private const val KEY = "currency"
    }
}
