package ru.vasev.glasses.claude

import java.text.Normalizer

/** Один завершённый обмен «вопрос — ответ». [jpeg] — снимок с очков, если вопрос был с ним. */
class Exchange(
    val id: Long,
    val question: String,
    val jpeg: ByteArray?,
    val answer: String,
)

/** Реплика в запросе к Claude. */
sealed interface Turn {
    /** [jpeg] — снимок, который отправляется картинкой; [photoPlaceholder] — старый снимок, заменённый на «[фото]». */
    data class User(val text: String, val jpeg: ByteArray? = null, val photoPlaceholder: Boolean = false) : Turn

    data class Assistant(val text: String) : Turn
}

/**
 * История разговора в памяти приложения.
 *
 * - Claude «помнит» только хвост истории: когда в памяти набирается [windowMax] обменов,
 *   остаются последние [windowKeep]. Обрезка пачкой, а не по одному, чтобы реже сбивать кэш промпта.
 * - Картинкой отправляется только самый свежий снимок; более старые заменяются пометкой «[фото]».
 * - Всё, что выпало из памяти, остаётся в [exchanges] для показа на экране.
 */
class Conversation(
    private val windowMax: Int = 10,
    private val windowKeep: Int = 5,
) {
    private val all = mutableListOf<Exchange>()
    private var nextId = 0L

    /** Индекс первого обмена, который Claude ещё помнит. */
    var memoryStart = 0
        private set

    val exchanges: List<Exchange> get() = all.toList()

    val remembered: List<Exchange> get() = all.subList(memoryStart, all.size).toList()

    val isEmpty: Boolean get() = all.isEmpty()

    /** Реплики для запроса: запомненная история и новый вопрос (со снимком, если он есть). */
    fun turnsFor(question: String, jpeg: ByteArray? = null): List<Turn> {
        val memory = remembered
        // Новый снимок вытесняет все старые; без него картинкой остаётся последний снимок из памяти.
        val latestPhotoId = if (jpeg != null) null else memory.lastOrNull { it.jpeg != null }?.id
        return buildList {
            for (exchange in memory) {
                val keepImage = exchange.id == latestPhotoId
                add(
                    Turn.User(
                        text = exchange.question,
                        jpeg = exchange.jpeg.takeIf { keepImage },
                        photoPlaceholder = exchange.jpeg != null && !keepImage,
                    ),
                )
                add(Turn.Assistant(exchange.answer))
            }
            add(Turn.User(question, jpeg))
        }
    }

    /** Записывает завершённый обмен и при необходимости обрезает память. */
    fun add(question: String, jpeg: ByteArray?, answer: String): Exchange {
        val exchange = Exchange(nextId++, question, jpeg, answer)
        all += exchange
        if (all.size - memoryStart >= windowMax) memoryStart = all.size - windowKeep
        return exchange
    }

    fun clear() {
        all.clear()
        memoryStart = 0
    }

    companion object {
        /**
         * «Новый разговор», «Забудь всё!», «New conversation.» и т. п. — команды языка приложения
         * (`R.array.reset_commands`). Сравнение без учёта регистра, знаков препинания и диакритики
         * (NFD + удаление диакритических знаков: «ё» → «е»; команды нормализуются так же, как фраза).
         */
        fun isResetCommand(text: String, commands: Collection<String>): Boolean = isCommand(text, commands)

        /** Фраза целиком — одна из [commands] (так же сравниваются «стоп» / «stop» во время ответа). */
        fun isCommand(text: String, commands: Collection<String>): Boolean =
            normalize(text) in commands.map(::normalize)

        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
