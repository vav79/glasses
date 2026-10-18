package ru.vasev.glasses.llm

import org.json.JSONObject

/**
 * Инструмент на стороне приложения (заметки, курсы валют): описание для модели и JSON Schema аргументов.
 * Схема — обычные Map/List, чтобы из неё собирали запрос оба провайдера (SDK Anthropic и org.json).
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Any>,
    val required: List<String>,
)

/** Вызов инструмента моделью. [input] — разобранные аргументы. */
data class ToolCall(val id: String, val name: String, val input: JSONObject)

/** Результат для модели (обычно JSON-строка). [isError] — инструмент не справился, модель должна это учесть. */
data class ToolOutput(val content: String, val isError: Boolean = false)

/** Набор инструментов запроса и их исполнитель. Исполнитель не бросает исключений — ошибки возвращает в [ToolOutput]. */
class ToolSet(val specs: List<ToolSpec>, val execute: suspend (ToolCall) -> ToolOutput) {
    val isEmpty: Boolean get() = specs.isEmpty()
}

/**
 * Веб-поиск на стороне сервера (только Claude). [country] (ISO 3166-1 alpha-2) и [timezone] (IANA) —
 * примерное место пользователя, чтобы «часы работы рядом» и «погода» искались там, где он сейчас.
 */
data class WebSearch(val maxUses: Int, val country: String?, val timezone: String?)

/** Источник из веб-поиска — ссылкой под ответом. */
data class WebSource(val url: String, val title: String) {
    /** Домен без «www.» — подпись ссылки, если заголовок пустой. */
    val domain: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

/** Сколько раз подряд модель может вызвать инструменты в одном ответе — защита от зацикливания. */
const val MAX_TOOL_ROUNDS = 5

/** Аргумент-строка; пустая и отсутствующая — null. */
fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else optString(name).trim().ifEmpty { null }
