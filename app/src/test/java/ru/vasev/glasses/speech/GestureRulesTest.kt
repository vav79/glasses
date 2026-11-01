package ru.vasev.glasses.speech

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRulesTest {

    @Test
    fun `коды кнопок — касания`() {
        assertEquals(Tap.SINGLE, Tap.of(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(Tap.SINGLE, Tap.of(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(Tap.SINGLE, Tap.of(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(Tap.DOUBLE, Tap.of(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(Tap.of(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `во время ответа перебивает любое касание`() {
        assertEquals(GestureAction.INTERRUPT, GestureRules.action(Tap.SINGLE, answering = true, chosen = Tap.DOUBLE))
        assertEquals(GestureAction.INTERRUPT, GestureRules.action(Tap.DOUBLE, answering = true, chosen = Tap.DOUBLE))
        // Даже с выключенными жестами: кнопка гарнитуры перебивала и раньше.
        assertEquals(GestureAction.INTERRUPT, GestureRules.action(Tap.SINGLE, answering = true, chosen = null))
    }

    @Test
    fun `в покое вопрос начинает только выбранный жест`() {
        assertEquals(GestureAction.ASK, GestureRules.action(Tap.DOUBLE, answering = false, chosen = Tap.DOUBLE))
        assertEquals(GestureAction.IGNORE, GestureRules.action(Tap.SINGLE, answering = false, chosen = Tap.DOUBLE))
        assertEquals(GestureAction.ASK, GestureRules.action(Tap.SINGLE, answering = false, chosen = Tap.SINGLE))
        assertEquals(GestureAction.IGNORE, GestureRules.action(Tap.DOUBLE, answering = false, chosen = null))
    }

    @Test
    fun `когда ловить касания`() {
        fun state(
            chosen: Tap? = Tap.DOUBLE,
            glasses: Boolean = true,
            playing: Boolean = false,
            endedAt: Long? = null,
            call: Boolean = false,
            now: Long = 100_000L,
        ) = GestureRules.state(chosen, glasses, playing, endedAt, call, now)

        assertEquals(GestureState.READY, state())
        assertEquals(GestureState.OFF, state(chosen = null))
        assertEquals(GestureState.NO_GLASSES, state(glasses = false))
        assertEquals(GestureState.CALL, state(call = true))
        assertEquals(GestureState.OTHER_MEDIA, state(playing = true))
        // Музыка замолчала 59 с назад — ещё ждём, 60 с — уже можно.
        assertEquals(GestureState.MEDIA_COOLDOWN, state(endedAt = 41_000L))
        assertEquals(GestureState.READY, state(endedAt = 40_000L))
    }

    @Test
    fun `пауза и play подряд — двойное касание`() {
        // PAUSE ничего не сделал (ждём двойное), PLAY через 0,5 с — это двойное.
        assertTrue(GestureRules.pairsIntoDouble(Tap.SINGLE, 10_000L, previousIgnored = true, tap = Tap.SINGLE, now = 10_500L))
        // Через 0,9 с — уже отдельное касание.
        assertFalse(GestureRules.pairsIntoDouble(Tap.SINGLE, 10_000L, previousIgnored = true, tap = Tap.SINGLE, now = 10_900L))
        // Первое касание перебило ответ — второе не превращаем в новый вопрос.
        assertFalse(GestureRules.pairsIntoDouble(Tap.SINGLE, 10_000L, previousIgnored = false, tap = Tap.SINGLE, now = 10_500L))
        assertFalse(GestureRules.pairsIntoDouble(Tap.DOUBLE, 10_000L, previousIgnored = true, tap = Tap.SINGLE, now = 10_500L))
        assertFalse(GestureRules.pairsIntoDouble(null, null, previousIgnored = true, tap = Tap.SINGLE, now = 10_500L))
    }

    @Test
    fun `антидребезг`() {
        assertTrue(GestureRules.accept(null, 5_000L))
        assertFalse(GestureRules.accept(5_000L, 5_500L))
        assertTrue(GestureRules.accept(5_000L, 6_500L))
    }
}
