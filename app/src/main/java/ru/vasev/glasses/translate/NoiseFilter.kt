package ru.vasev.glasses.translate

import android.content.Context
import ru.vasev.glasses.R

/**
 * Отсекает фразы, которые не стоит отправлять в перевод: пустые или из одного-двух шумовых звуков
 * («э», «эм хм», «uh», «嗯»). Короткие осмысленные ответы («да», «no», «是», «evet») проходят.
 * Сами междометия — данные, а не код: `res/raw/noise_fillers.txt` ([load]).
 */
class NoiseFilter(private val fillers: Set<String>) {

    fun isNoise(text: String): Boolean {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        if (words.size > 2) return false
        return words.all { word ->
            val collapsed = collapseRepeats(word)
            collapsed in fillers || collapsed.isSingleNonIdeographicLetter()
        }
    }

    private fun collapseRepeats(word: String): String {
        val out = StringBuilder()
        var previous = -1
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (cp != previous) out.appendCodePoint(cp)
            previous = cp
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** Одиночная буква алфавита («a», «я») — шум; одиночный иероглиф («是», «好») — осмысленное слово. */
    private fun String.isSingleNonIdeographicLetter(): Boolean {
        if (codePointCount(0, length) != 1) return false
        val cp = codePointAt(0)
        return Character.isLetter(cp) && !Character.isIdeographic(cp)
    }

    companion object {
        /** Разбор файла междометий: по одному на строку, `#` — комментарий. */
        fun parse(text: String): Set<String> = text.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

        fun load(context: Context): NoiseFilter =
            NoiseFilter(parse(context.resources.openRawResource(R.raw.noise_fillers).bufferedReader(Charsets.UTF_8).use { it.readText() }))
    }
}
