package ru.vasev.glasses.nav

import java.util.Locale

/**
 * На каком языке показать адрес таксисту: язык страны, где цель. Таблица — основной язык улиц и вывесок
 * (не все официальные); страны, где таксисты читают латиницу по-английски, — `en`. Чистая логика, тесты.
 */
object CountryLanguage {

    private val BY_COUNTRY: Map<String, String> = buildMap {
        put("TH", "th"); put("VN", "vi"); put("CN", "zh"); put("TW", "zh-TW"); put("HK", "zh-HK"); put("MO", "zh-HK")
        put("JP", "ja"); put("KR", "ko"); put("KH", "km"); put("LA", "lo"); put("MM", "my"); put("LK", "si"); put("NP", "ne")
        put("BD", "bn"); put("IN", "hi"); put("PK", "ur"); put("ID", "id"); put("MY", "ms"); put("PH", "en"); put("SG", "en")
        listOf("AE", "SA", "EG", "QA", "JO", "KW", "BH", "OM", "LB", "IQ", "MA", "TN", "DZ").forEach { put(it, "ar") }
        put("IL", "he"); put("IR", "fa"); put("TR", "tr"); put("GE", "ka"); put("AM", "hy"); put("AZ", "az"); put("GR", "el"); put("CY", "el")
        listOf("RU", "BY", "KZ", "KG").forEach { put(it, "ru") }
        put("UZ", "uz"); put("UA", "uk"); put("MN", "mn"); put("RS", "sr"); put("BG", "bg"); put("MK", "mk"); put("ME", "sr")
        listOf("ES", "MX", "AR", "CO", "PE", "CL", "CU", "DO", "EC", "UY", "VE", "BO", "PY", "CR", "PA", "GT").forEach { put(it, "es") }
        put("PT", "pt"); put("BR", "pt"); put("FR", "fr"); put("BE", "fr"); put("MC", "fr"); put("DE", "de"); put("AT", "de"); put("CH", "de")
        put("IT", "it"); put("NL", "nl"); put("PL", "pl"); put("CZ", "cs"); put("SK", "sk"); put("HU", "hu"); put("RO", "ro"); put("MD", "ro")
        put("HR", "hr"); put("SI", "sl"); put("FI", "fi"); put("SE", "sv"); put("NO", "nb"); put("DK", "da"); put("IS", "is")
        put("EE", "et"); put("LV", "lv"); put("LT", "lt"); put("AL", "sq"); put("ET", "am")
        listOf("US", "GB", "IE", "AU", "NZ", "CA", "ZA", "KE", "NG", "MT", "MV").forEach { put(it, "en") }
    }

    /** Языки, которые можно выбрать вручную (теги BCP-47), по одному разу. */
    val all: List<String> = BY_COUNTRY.values.distinct()

    fun forCountry(country: String?): String? = country?.uppercase()?.let(BY_COUNTRY::get)

    /** Локаль для геокодера: язык и страна (страна помогает с вариантом письма, например zh-TW). */
    fun locale(language: String, country: String?): Locale {
        val base = Locale.forLanguageTag(language)
        return if (country != null && base.country.isEmpty()) Locale(base.language, country.uppercase()) else base
    }

    /** Название языка на английском — для промпта («Thai»). */
    fun englishName(language: String): String = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH)

    /**
     * Написан ли текст письмом этого языка: геокодер, если у него нет данных на нужном языке, молча отдаёт
     * латиницу — такой «тайский» адрес таксисту не поможет. Для языков на латинице проверять нечего.
     */
    fun scriptMatches(text: String, language: String): Boolean {
        val script = SCRIPTS[Locale.forLanguageTag(language).language] ?: return true
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return false
        val own = letters.count { Character.UnicodeScript.of(it.code) in script }
        return own * 2 >= letters.length
    }

    private val SCRIPTS: Map<String, Set<Character.UnicodeScript>> = mapOf(
        "th" to setOf(Character.UnicodeScript.THAI),
        "zh" to setOf(Character.UnicodeScript.HAN),
        "ja" to setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA),
        "ko" to setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN),
        "km" to setOf(Character.UnicodeScript.KHMER),
        "lo" to setOf(Character.UnicodeScript.LAO),
        "my" to setOf(Character.UnicodeScript.MYANMAR),
        "si" to setOf(Character.UnicodeScript.SINHALA),
        "ne" to setOf(Character.UnicodeScript.DEVANAGARI),
        "hi" to setOf(Character.UnicodeScript.DEVANAGARI),
        "bn" to setOf(Character.UnicodeScript.BENGALI),
        "ur" to setOf(Character.UnicodeScript.ARABIC),
        "ar" to setOf(Character.UnicodeScript.ARABIC),
        "fa" to setOf(Character.UnicodeScript.ARABIC),
        "he" to setOf(Character.UnicodeScript.HEBREW),
        "ka" to setOf(Character.UnicodeScript.GEORGIAN),
        "hy" to setOf(Character.UnicodeScript.ARMENIAN),
        "el" to setOf(Character.UnicodeScript.GREEK),
        "am" to setOf(Character.UnicodeScript.ETHIOPIC),
        "ru" to setOf(Character.UnicodeScript.CYRILLIC),
        "uk" to setOf(Character.UnicodeScript.CYRILLIC),
        "bg" to setOf(Character.UnicodeScript.CYRILLIC),
        "mk" to setOf(Character.UnicodeScript.CYRILLIC),
        "mn" to setOf(Character.UnicodeScript.CYRILLIC),
    )
}
