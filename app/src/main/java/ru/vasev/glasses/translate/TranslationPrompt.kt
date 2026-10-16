package ru.vasev.glasses.translate

import java.util.Locale

/** Язык собеседника: код распознавания/синтеза и названия для интерфейса и промпта. */
enum class PartnerLanguage(
    val tag: String,
    val title: String,
    /** «с английского» */
    val genitive: String,
    /** «на английский» */
    val accusative: String,
) {
    ENGLISH("en-US", "Английский", "английского", "английский"),
    CHINESE("zh-CN", "Китайский", "китайского", "китайский"),
    TURKISH("tr-TR", "Турецкий", "турецкого", "турецкий"),
    GERMAN("de-DE", "Немецкий", "немецкого", "немецкий"),
    FRENCH("fr-FR", "Французский", "французского", "французский"),
    SPANISH("es-ES", "Испанский", "испанского", "испанский"),
    ITALIAN("it-IT", "Итальянский", "итальянского", "итальянский"),
    ARABIC("ar-SA", "Арабский", "арабского", "арабский"),
    THAI("th-TH", "Тайский", "тайского", "тайский"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(tag)
}

/** Кто говорит. */
enum class Who { PARTNER, ME }

/** Реплика разговора: [original] — что сказали, [translation] — перевод (null, если перевода нет). */
data class Line(val who: Who, val original: String, val translation: String?)

/** Сборка пользовательского сообщения для переводчика: контекст, направление и фраза. */
object TranslationPrompt {

    const val CONTEXT_SIZE = 3

    const val SYSTEM = "Ты устный переводчик. Выводи только перевод, без пояснений и кавычек. " +
        "Сохраняй смысл и разговорный тон. Имена, числа и цены передавай точно."

    /** [who] сказал [phrase]; [history] — все предыдущие реплики, в промпт попадут последние [CONTEXT_SIZE]. */
    fun build(language: PartnerLanguage, who: Who, phrase: String, history: List<Line>): String = buildString {
        val context = history.takeLast(CONTEXT_SIZE)
        if (context.isNotEmpty()) {
            appendLine("Предыдущие реплики разговора — только для понимания контекста, их не переводи:")
            for (line in context) {
                append(if (line.who == Who.PARTNER) "Собеседник: " else "Я: ")
                append(line.original)
                line.translation?.let { append(" (перевод: ").append(it).append(')') }
                appendLine()
            }
            appendLine()
        }
        if (who == Who.PARTNER) {
            appendLine("Переведи с ${language.genitive} на русский:")
        } else {
            appendLine("Переведи с русского на ${language.accusative}:")
        }
        append(phrase.trim())
    }
}
