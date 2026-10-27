package ru.vasev.glasses.speech

/** Какой Bluetooth-гарнитурой слушать мой голос и как узнать среди них очки. Чистая логика под тестами. */
object HeadsetPick {

    /**
     * Имена очков Meta в Bluetooth, если DAT их ещё не сообщил (не зарегистрированы, Meta AI не запущен).
     * У Ray-Ban Meta Gen 2 Bluetooth-имя совпадает с именем в Meta AI: «RB Meta 0000».
     */
    private val KNOWN_PREFIXES = listOf("RB Meta", "Ray-Ban Meta", "Meta Ray-Ban", "Oakley Meta")

    fun isGlasses(bluetoothName: String, datNames: Collection<String>): Boolean {
        val name = bluetoothName.trim()
        if (name.isEmpty()) return false
        return datNames.any { it.trim().equals(name, ignoreCase = true) } ||
            KNOWN_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    /** «Очки» — только очки; «Авто» и «Гарнитура» — очки в приоритете, иначе любая; «Телефон» — никакая. */
    fun <T> pick(choice: MicChoice, candidates: List<T>, isGlasses: (T) -> Boolean): T? = when (choice) {
        MicChoice.PHONE -> null
        MicChoice.GLASSES -> candidates.firstOrNull(isGlasses)
        MicChoice.AUTO, MicChoice.HEADSET -> candidates.firstOrNull(isGlasses) ?: candidates.firstOrNull()
    }
}
