package ru.vasev.glasses.nav

import ru.vasev.glasses.places.PlaceKind
import java.text.Normalizer

/** Голосовая команда навигации — разбирается до модели, с любой вкладки, где микрофон слушает меня. */
sealed interface NavCommand {
    /** «Веди в …», «как добраться до …»: моё место или адрес. */
    data class GoTo(val query: String) : NavCommand

    /** «Где машина» — к моему месту этого вида. */
    data class GoToPlace(val kind: PlaceKind) : NavCommand

    /** «Как вернуться» — в отель, если сохранён, иначе домой. */
    data object Return : NavCommand

    /** «Назад к началу маршрута» — туда, где был, когда выбрал цель. */
    data object BackToStart : NavCommand

    /** «Покажи адрес таксисту» — текущая цель, иначе отель. */
    data object ShowTaxi : NavCommand

    /** «Сколько осталось?» */
    data object HowFar : NavCommand

    /** «Запомни, это мой отель» / «Запомни, здесь машина» — место здесь, а не заметка. */
    data class RememberPlace(val kind: PlaceKind) : NavCommand
}

/** Фразы команд на языке приложения (из ресурсов). */
data class NavVocabulary(
    val goPrefixes: List<String>,
    val returnPhrases: List<String>,
    val backToStartPhrases: List<String>,
    val taxiPhrases: List<String>,
    val howFarPhrases: List<String>,
    val whereCarPhrases: List<String>,
    val rememberTriggers: List<String>,
    val rememberPlace: Map<PlaceKind, List<String>>,
)

/** Разбор фразы в команду навигации. Чистая логика, тесты. null — не команда (вопрос уходит модели). */
object NavCommands {

    fun parse(text: String, v: NavVocabulary): NavCommand? {
        val n = normalize(text)
        if (n.isEmpty()) return null
        fun matches(list: List<String>) = list.any { normalize(it) == n }
        when {
            matches(v.returnPhrases) -> return NavCommand.Return
            matches(v.backToStartPhrases) -> return NavCommand.BackToStart
            matches(v.taxiPhrases) -> return NavCommand.ShowTaxi
            matches(v.howFarPhrases) -> return NavCommand.HowFar
            matches(v.whereCarPhrases) -> return NavCommand.GoToPlace(PlaceKind.CAR)
        }
        // «Запомни, это мой отель»: после слова-триггера — фраза места целиком. Иначе это обычная заметка.
        v.rememberTriggers.map(::normalize).filter { it.isNotEmpty() && n.startsWith("$it ") }.maxByOrNull { it.length }?.let { trigger ->
            val rest = n.removePrefix(trigger).trim()
            v.rememberPlace.forEach { (kind, phrases) -> if (phrases.any { normalize(it) == rest }) return NavCommand.RememberPlace(kind) }
            return null
        }
        val rest = NavQuery.stripPrefix(text, v.goPrefixes)
        return if (normalize(rest) != n && rest.isNotBlank()) NavCommand.GoTo(rest) else null
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
