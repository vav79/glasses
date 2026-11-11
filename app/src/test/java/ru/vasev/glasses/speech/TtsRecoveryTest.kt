package ru.vasev.glasses.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsRecoveryTest {

    /** Попытки по очереди из [results]; считает попытки и пересоздания. */
    private class Script(vararg results: TtsAttempt) {
        private val queue = ArrayDeque(results.toList())
        var attempts = 0
        val recreated = mutableListOf<TtsFailure>()

        fun run(): TtsAttempt = runBlocking {
            TtsRecovery.speak(
                attempt = {
                    attempts++
                    queue.removeFirst()
                },
                recreate = { recreated += it },
            )
        }
    }

    @Test
    fun `сказали с первого раза — без пересоздания`() {
        val s = Script(TtsAttempt.Spoken)
        assertEquals(TtsAttempt.Spoken, s.run())
        assertEquals(1, s.attempts)
        assertEquals(emptyList<TtsFailure>(), s.recreated)
    }

    @Test
    fun `движок отвалился — пересоздать и повторить фразу один раз`() {
        for (reason in TtsFailure.entries) {
            val s = Script(TtsAttempt.Failed(reason), TtsAttempt.Spoken)
            assertEquals(TtsAttempt.Spoken, s.run())
            assertEquals(2, s.attempts)
            assertEquals(listOf(reason), s.recreated)
        }
    }

    @Test
    fun `и после пересоздания не вышло — ошибка, третьей попытки нет`() {
        val s = Script(TtsAttempt.Failed(TtsFailure.CALL_ERROR), TtsAttempt.Failed(TtsFailure.NO_START))
        assertEquals(TtsAttempt.Failed(TtsFailure.NO_START), s.run())
        assertEquals(2, s.attempts)
        assertEquals(1, s.recreated.size)
    }

    @Test
    fun `голоса для языка нет — не пересоздаём, повтор не поможет`() {
        val s = Script(TtsAttempt.NoVoice)
        assertEquals(TtsAttempt.NoVoice, s.run())
        assertEquals(1, s.attempts)
        assertEquals(emptyList<TtsFailure>(), s.recreated)
    }

    @Test
    fun `setLanguage — язык выставлен`() {
        assertNull(TtsRecovery.language(0, bound = true, workedBefore = false)) // LANG_AVAILABLE
        assertNull(TtsRecovery.language(2, bound = false, workedBefore = false)) // LANG_COUNTRY_VAR_AVAILABLE
    }

    @Test
    fun `setLanguage — отказ отвязанного движка или языка, который работал, это отвал`() {
        val lost = TtsAttempt.Failed(TtsFailure.LANGUAGE_LOST)
        // Баг 05.11: «not bound to TTS engine» → LANG_NOT_SUPPORTED → «Нет голоса для языка «русский»».
        assertEquals(lost, TtsRecovery.language(TtsRecovery.LANG_NOT_SUPPORTED, bound = false, workedBefore = false))
        assertEquals(lost, TtsRecovery.language(TtsRecovery.LANG_MISSING_DATA, bound = true, workedBefore = true))
    }

    @Test
    fun `setLanguage — живой движок, язык ни разу не работал — голоса нет`() {
        assertEquals(TtsAttempt.NoVoice, TtsRecovery.language(TtsRecovery.LANG_MISSING_DATA, bound = true, workedBefore = false))
        assertEquals(TtsAttempt.NoVoice, TtsRecovery.language(TtsRecovery.LANG_NOT_SUPPORTED, bound = true, workedBefore = false))
    }
}
