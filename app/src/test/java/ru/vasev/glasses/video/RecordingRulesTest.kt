package ru.vasev.glasses.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRulesTest {

    private val frame = 33_333L

    @Test
    fun `время куска — с нуля по часам очков`() {
        val t = SegmentTimeline()
        assertEquals(0L, t.map(5_000_000, frame))
        assertEquals(frame, t.map(5_000_000 + frame, frame))
        assertEquals(2 * frame, t.map(5_000_000 + 2 * frame, frame))
    }

    @Test
    fun `пауза касанием вырезается — один шаг кадра вместо дыры`() {
        val t = SegmentTimeline()
        t.map(0, frame)
        t.map(frame, frame)
        // 14 с паузы: следующий кадр идёт сразу за предыдущим.
        assertEquals(2 * frame, t.map(frame + 14_000_000, frame))
        assertEquals(3 * frame, t.map(2 * frame + 14_000_000, frame))
    }

    @Test
    fun `время не идёт назад и не повторяется`() {
        val t = SegmentTimeline()
        t.map(1_000_000, frame)
        val a = t.map(1_000_000 + frame, frame)
        val b = t.map(1_000_000 + frame, frame)  // тот же pts
        val c = t.map(900_000, frame)            // часы назад
        assertTrue(b > a)
        assertTrue(c > b)
    }

    @Test
    fun `разряд, разрешение и место — стоп, остальное — повтор`() {
        assertEquals(RecoveryAction.STOP, RecordingRules.action(StopReason.BATTERY_LOW))
        assertEquals(RecoveryAction.STOP, RecordingRules.action(StopReason.LOW_STORAGE))
        assertEquals(RecoveryAction.STOP, RecordingRules.action(StopReason.PERMISSION))
        assertEquals(RecoveryAction.RETRY, RecordingRules.action(StopReason.HINGE_CLOSED))
        assertEquals(RecoveryAction.RETRY, RecordingRules.action(StopReason.THERMAL))
        assertEquals(RecoveryAction.RETRY, RecordingRules.action(StopReason.LINK_LOST))
    }

    @Test
    fun `повтор — 5, 10, 20 с до минуты, перегрев — минута`() {
        assertEquals(5_000L, RecordingRules.retryDelayMs(StopReason.LINK_LOST, 1))
        assertEquals(10_000L, RecordingRules.retryDelayMs(StopReason.LINK_LOST, 2))
        assertEquals(20_000L, RecordingRules.retryDelayMs(StopReason.LINK_LOST, 3))
        assertEquals(60_000L, RecordingRules.retryDelayMs(StopReason.LINK_LOST, 9))
        assertEquals(60_000L, RecordingRules.retryDelayMs(StopReason.THERMAL, 1))
    }

    @Test
    fun `новый кусок — только на ключевом кадре, через 10 минут или при смене описания`() {
        assertFalse(RecordingRules.startNewSegment(isKey = false, segmentUs = RecordingRules.SEGMENT_US + 1, parametersChanged = true))
        assertTrue(RecordingRules.startNewSegment(isKey = true, segmentUs = RecordingRules.SEGMENT_US, parametersChanged = false))
        assertTrue(RecordingRules.startNewSegment(isKey = true, segmentUs = 1_000_000, parametersChanged = true))
        assertFalse(RecordingRules.startNewSegment(isKey = true, segmentUs = 1_000_000, parametersChanged = false))
    }
}
