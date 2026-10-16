package ru.vasev.glasses.translate

/**
 * Отсекает фразы, которые не стоит отправлять в перевод: пустые или из одного-двух шумовых звуков
 * («э», «эм хм», «uh», «嗯»). Короткие осмысленные ответы («да», «no», «是», «evet») проходят.
 */
object NoiseFilter {

    /** Междометия-заполнители после схлопывания повторов («ээээ» → «э», «hmmm» → «hm»). */
    private val FILLERS = setOf(
        // русский
        "э", "эм", "м", "мх", "хм", "а", "ах", "ох", "ну", "ы",
        // английский
        "uh", "um", "hm", "er", "erm", "ah", "eh", "oh", "uhm", "mh",
        // немецкий, французский, испанский, итальянский
        "äh", "ähm", "öh", "euh", "bah", "ehm", "mm",
        // турецкий
        "e", "ı", "şey",
        // китайский
        "嗯", "啊", "哦", "呃", "额", "唔",
        // арабский
        "اه", "آه", "ام", "امم", "اممم",
        // тайский
        "อืม", "เอ่อ", "อ่า", "อือ",
    )

    fun isNoise(text: String): Boolean {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        if (words.size > 2) return false
        return words.all { word ->
            val collapsed = collapseRepeats(word)
            collapsed in FILLERS || collapsed.isSingleNonIdeographicLetter()
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
}
