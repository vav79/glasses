package ru.vasev.glasses.translate

import androidx.annotation.StringRes
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.AppLanguage
import java.util.Locale

/**
 * Язык разговора в «Переводе»: код распознавания/синтеза, английское имя для промпта и название в интерфейсе.
 * «Мой язык» — язык приложения ([forApp]); язык собеседника выбирается из остальных ([partnersFor]).
 */
enum class PartnerLanguage(
    val tag: String,
    /** Имя для промпта модели (промпты на английском). */
    val englishName: String,
    @StringRes val titleRes: Int,
    /** Флаг для списка языков и ленты. */
    val flag: String,
) {
    ENGLISH("en-US", "English", R.string.lang_english, "🇬🇧"),
    RUSSIAN("ru-RU", "Russian", R.string.lang_russian, "🇷🇺"),
    CHINESE("zh-CN", "Chinese (Mandarin, simplified)", R.string.lang_chinese, "🇨🇳"),
    TURKISH("tr-TR", "Turkish", R.string.lang_turkish, "🇹🇷"),
    GERMAN("de-DE", "German", R.string.lang_german, "🇩🇪"),
    FRENCH("fr-FR", "French", R.string.lang_french, "🇫🇷"),
    SPANISH("es-ES", "Spanish", R.string.lang_spanish, "🇪🇸"),
    ITALIAN("it-IT", "Italian", R.string.lang_italian, "🇮🇹"),
    ARABIC("ar-SA", "Arabic", R.string.lang_arabic, "🇸🇦"),
    THAI("th-TH", "Thai", R.string.lang_thai, "🇹🇭"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        /** «Мой язык» в переводе = язык приложения. */
        fun forApp(language: AppLanguage): PartnerLanguage = when (language) {
            AppLanguage.RUSSIAN -> RUSSIAN
            AppLanguage.ENGLISH -> ENGLISH
        }

        /** Языки собеседника: все, кроме моего. */
        fun partnersFor(my: PartnerLanguage): List<PartnerLanguage> = entries.filter { it != my }

        /** Сохранённый язык собеседника, если он совпал с моим (сменили язык приложения), — первый из доступных. */
        fun resolvePartner(saved: PartnerLanguage?, my: PartnerLanguage): PartnerLanguage =
            saved?.takeIf { it != my } ?: partnersFor(my).first()
    }
}

/** Кто говорит. */
enum class Who { PARTNER, ME }

/** Реплика разговора: [original] — что сказали, [translation] — перевод (null, если перевода нет). */
data class Line(val who: Who, val original: String, val translation: String?)

/** Сборка пользовательского сообщения для переводчика: контекст, направление и фраза. Промпты — на английском. */
object TranslationPrompt {

    const val CONTEXT_SIZE = 3

    const val SYSTEM = "You are a live interpreter. Output only the translation, without explanations or quotation marks. " +
        "Preserve the meaning and the conversational tone. Render names, numbers and prices exactly."

    /**
     * [who] сказал [phrase]; [my] — мой язык, [partner] — язык собеседника;
     * [history] — все предыдущие реплики, в промпт попадут последние [CONTEXT_SIZE].
     */
    fun build(partner: PartnerLanguage, my: PartnerLanguage, who: Who, phrase: String, history: List<Line>): String = buildString {
        val context = history.takeLast(CONTEXT_SIZE)
        if (context.isNotEmpty()) {
            appendLine("Earlier lines of the conversation — for context only, do not translate them:")
            for (line in context) {
                append(if (line.who == Who.PARTNER) "Partner: " else "Me: ")
                append(line.original)
                line.translation?.let { append(" (translation: ").append(it).append(')') }
                appendLine()
            }
            appendLine()
        }
        val (from, to) = if (who == Who.PARTNER) partner to my else my to partner
        appendLine("Translate from ${from.englishName} into ${to.englishName}:")
        append(phrase.trim())
    }
}
