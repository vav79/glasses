package ru.vasev.glasses.answer

/**
 * Двухуровневый ответ из потока модели: суть (до маркера [MARKER]) озвучивается по предложениям,
 * подробности (после маркера) — только в чате. Маркер может прийти частями в разных дельтах — хвост,
 * похожий на начало маркера, придерживается до следующей дельты. Маркер на экран и в память не попадает.
 *
 * Страховка: если модель забыла маркер, озвучиваются первые [maxSpoken] предложения, остальное — подробности.
 */
class SpokenAnswer(private val splitter: SentenceSplitter, private val maxSpoken: Int = MAX_SPOKEN) {

    private val summary = StringBuilder()
    private val details = StringBuilder()
    private val pending = StringBuilder()
    private var inDetails = false
    private var spokenCount = 0

    /** Суть — для пузыря чата, пока ответ идёт. */
    val summaryText: String get() = summary.toString().trim()

    /** Подробности без маркера. */
    val detailsText: String get() = details.toString().trim()

    /** Полный ответ без маркера — для памяти разговора. */
    val fullText: String get() = listOf(summaryText, detailsText).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** Очередная дельта; возвращает предложения для озвучки. */
    fun feed(delta: String): List<String> {
        if (inDetails) {
            details.append(delta)
            return emptyList()
        }
        pending.append(delta)
        val markerAt = pending.indexOf(MARKER)
        if (markerAt >= 0) {
            val before = pending.substring(0, markerAt)
            details.append(pending.substring(markerAt + MARKER.length))
            pending.setLength(0)
            val spoken = speak(before, final = true)
            inDetails = true
            return spoken
        }
        // Придержать хвост, который может оказаться началом маркера.
        val keep = (MARKER.length - 1 downTo 1).firstOrNull { pending.endsWith(MARKER.substring(0, it)) } ?: 0
        val ready = pending.substring(0, pending.length - keep)
        pending.delete(0, pending.length - keep)
        return speak(ready, final = false)
    }

    /** Конец потока: остаток сути. */
    fun finish(): List<String> {
        if (inDetails) return emptyList()
        val rest = pending.toString()
        pending.setLength(0)
        return speak(rest, final = true)
    }

    private fun speak(text: String, final: Boolean): List<String> {
        val sentences = splitter.feed(text) + if (final) splitter.finish() else emptyList()
        val out = mutableListOf<String>()
        for (sentence in sentences) {
            if (spokenCount < maxSpoken) {
                summary.append(sentence).append(' ')
                out += sentence
                spokenCount++
            } else {
                // Маркера нет, а суть уже длинная — остальное только в чат.
                details.append(sentence).append(' ')
                inDetails = true
            }
        }
        if (inDetails && !final) {
            // Всё, что ещё не нарезано, тоже уходит в подробности.
            details.append(splitter.finish().joinToString(" "))
        }
        return out
    }

    companion object {
        /** Служебный маркер между сутью и подробностями (его же называет системный промпт). */
        const val MARKER = "⟦more⟧"
        const val MAX_SPOKEN = 3
    }
}
