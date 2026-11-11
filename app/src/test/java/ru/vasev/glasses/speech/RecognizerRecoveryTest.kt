package ru.vasev.glasses.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerRecoveryTest {

    @Test
    fun `служба отвалилась до начала прослушивания — новый распознаватель`() {
        // После перезапуска процесса Google старый SpeechRecognizer падал так на каждом вопросе жестом.
        assertTrue(RecognizerRecovery.needsNew(RecognizerRecovery.ERROR_SERVER_DISCONNECTED, ready = false))
        assertTrue(RecognizerRecovery.needsNew(RecognizerRecovery.ERROR_CLIENT, ready = false))
    }

    @Test
    fun `обрыв посреди фразы — не пересоздаём (пересоздание само даёт код 11)`() {
        assertFalse(RecognizerRecovery.needsNew(RecognizerRecovery.ERROR_SERVER_DISCONNECTED, ready = true))
        assertFalse(RecognizerRecovery.needsNew(RecognizerRecovery.ERROR_CLIENT, ready = true))
    }

    @Test
    fun `тишина, сеть, разрешение — не про службу`() {
        for (code in listOf(1, 2, 3, 6, 7, 8, 9)) assertFalse(RecognizerRecovery.needsNew(code, ready = false))
    }
}
