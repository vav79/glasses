package ru.vasev.glasses.answer

import android.content.Context
import ru.vasev.glasses.R

/**
 * Режет потоковый текст модели на законченные предложения для озвучки — как только предложение закончилось,
 * не дожидаясь всего ответа. Не режет на числах («3.5», «$4.99», «12.10.2026»), сокращениях («т. е.», «ул.»,
 * «e.g.»; список — `res/raw/abbreviations.txt`), инициалах («А. С. Пушкин») и строчной букве после точки.
 */
class SentenceSplitter(abbreviations: Collection<String>) {

    /** Сокращения без пробелов, в нижнем регистре: «т. е.» → «т.е.». Значение — может ли сокращение завершать предложение («*»). */
    private val abbreviations: Map<String, Boolean> = abbreviations.associate { line ->
        val finalOk = line.trimEnd().endsWith("*")
        line.removeSuffix("*").lowercase().filterNot(Char::isWhitespace) to finalOk
    }

    private val buffer = StringBuilder()

    /** Добавляет кусок текста; возвращает предложения, которые точно закончились. */
    fun feed(text: String): List<String> {
        buffer.append(text)
        return drain(final = false)
    }

    /** Конец потока: всё, что осталось, — последнее предложение. */
    fun finish(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n') {
                emit(out, start, i)
                start = i + 1
            } else if (c in TERMINATORS) {
                // Серия знаков: «?!», «...», «.»» — граница после последнего.
                var end = i + 1
                while (end < buffer.length && (buffer[end] in TERMINATORS || buffer[end] in CLOSERS)) end++
                when (boundaryAt(i, end, final)) {
                    Boundary.YES -> {
                        emit(out, start, end)
                        start = end
                    }
                    Boundary.UNKNOWN -> break // ждём следующий кусок, чтобы увидеть, что после знака
                    Boundary.NO -> Unit
                }
                i = end
                continue
            }
            i++
        }
        if (final) {
            emit(out, start, buffer.length)
            buffer.setLength(0)
        } else {
            buffer.delete(0, start)
        }
        return out
    }

    private fun boundaryAt(markAt: Int, end: Int, final: Boolean): Boundary {
        // После знака должен быть пробел, а после пробелов — начало нового предложения.
        if (end == buffer.length) return if (final) Boundary.YES else Boundary.UNKNOWN
        if (!buffer[end].isWhitespace()) return Boundary.NO
        var next = end
        while (next < buffer.length && buffer[next].isWhitespace()) next++
        if (next == buffer.length) return if (final) Boundary.YES else Boundary.UNKNOWN
        // Дальше строчная буква — фраза продолжается («Ну… посмотрим», «см. ниже»).
        if (buffer[next].isLowerCase()) return Boundary.NO
        if (buffer[markAt] != '.') return Boundary.YES
        return when (abbreviationAt(markAt)) {
            Abbreviation.NONE, Abbreviation.SENTENCE_FINAL -> Boundary.YES
            Abbreviation.INSIDE -> Boundary.NO
        }
    }

    /** Чем завершается текст на точке [dotAt]: сокращением из списка, инициалом («А.») или обычным словом. */
    private fun abbreviationAt(dotAt: Int): Abbreviation {
        // Последнее «слово» с точками и пробелами внутри: «и т. д.» → «ит.д.», «ул.» → «ул.».
        var from = dotAt
        var letters = 0
        while (from > 0) {
            val p = buffer[from - 1]
            if (p.isLetter() || p == '.' || p == '-') {
                if (p.isLetter()) letters++
                from--
            } else if (p == ' ' && from >= 2 && buffer[from - 2] == '.') {
                from-- // пробел внутри «т. е.»
            } else {
                break
            }
        }
        val word = buffer.substring(from, dotAt + 1).lowercase().filterNot(Char::isWhitespace)
        // Длинное сокращение раньше короткого: «и т. д.» прежде «т. д.».
        val match = abbreviations.entries
            .filter { (abbr, _) -> word == abbr || word.endsWith(abbr) && word[word.length - abbr.length - 1] == '.' }
            .maxByOrNull { it.key.length }
        if (match != null) return if (match.value) Abbreviation.SENTENCE_FINAL else Abbreviation.INSIDE
        // Инициал: одна заглавная буква перед точкой, перед ней пробел или начало.
        val prev = buffer[dotAt - 1]
        val beforePrev = if (dotAt >= 2) buffer[dotAt - 2] else ' '
        val initial = letters >= 1 && prev.isUpperCase() && !beforePrev.isLetter() && word.length == 2
        return if (initial) Abbreviation.INSIDE else Abbreviation.NONE
    }

    private fun emit(out: MutableList<String>, from: Int, to: Int) {
        val sentence = buffer.substring(from, to).trim()
        if (sentence.any { it.isLetterOrDigit() }) out += sentence
    }

    private enum class Boundary { YES, NO, UNKNOWN }

    private enum class Abbreviation { NONE, INSIDE, SENTENCE_FINAL }

    companion object {
        private const val TERMINATORS = ".!?…"
        private const val CLOSERS = "»\"')]"

        fun parseAbbreviations(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        /** Список сокращений из `res/raw/abbreviations.txt` — читается один раз, нарезчик создаётся на каждый ответ. */
        fun loadAbbreviations(context: Context): List<String> =
            parseAbbreviations(context.resources.openRawResource(R.raw.abbreviations).bufferedReader().use { it.readText() })
    }
}
