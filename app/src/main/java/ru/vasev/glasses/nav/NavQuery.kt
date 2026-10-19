package ru.vasev.glasses.nav

import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import java.text.Normalizer

/**
 * Разбор фразы «Куда?»: «как добраться до вокзала» → «вокзала», «в отель» → мой отель.
 * Слова — из ресурсов (язык приложения), логика — чистая, под тестами.
 */
object NavQuery {

    /** Убирает начало вроде «как добраться до» / «take me to» (самое длинное из [prefixes]). */
    fun stripPrefix(text: String, prefixes: Collection<String>): String {
        val words = text.trim().split(Regex("\\s+"))
        val normalized = words.map(::normalize)
        val best = prefixes.map { p -> p.trim().split(Regex("\\s+")).map(::normalize) }
            .filter { p -> p.isNotEmpty() && p.size < words.size && normalized.subList(0, p.size) == p }
            .maxByOrNull { it.size }
        return (if (best == null) words else words.drop(best.size)).joinToString(" ").trim().trim(',', '.', '?', '!')
    }

    /**
     * Моё место по фразе: вся фраза — одно из названий вида ([aliases]: «отель», «в отель», «домой», «машина»…)
     * или название моего своего места. null — это не моё место (ищем адрес).
     */
    fun matchPlace(text: String, places: List<Place>, aliases: Map<PlaceKind, Collection<String>>): Place? {
        val key = normalize(text)
        if (key.isEmpty()) return null
        aliases.forEach { (kind, words) ->
            if (words.any { normalize(it) == key }) return places.firstOrNull { it.kind == kind } ?: return null
        }
        return places.firstOrNull { it.name.isNotBlank() && normalize(it.name) == key }
    }

    /** Вид места по фразе — даже если такого места ещё нет (подсказать «сохраните отель»). */
    fun kindOf(text: String, aliases: Map<PlaceKind, Collection<String>>): PlaceKind? {
        val key = normalize(text)
        return aliases.entries.firstOrNull { (_, words) -> words.any { normalize(it) == key } }?.key
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
