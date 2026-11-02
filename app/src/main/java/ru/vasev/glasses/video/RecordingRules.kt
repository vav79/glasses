package ru.vasev.glasses.video

/**
 * Время кадров в куске MP4: первый кадр — 0, дальше по часам очков; пауза потока (касание дужки) вырезается —
 * вместо дыры один шаг кадра, чтобы ролик не стоял на месте. Время всегда растёт (MediaMuxer требует).
 * Звук считается отдельно — по числу записанных сэмплов (пока видео на паузе, звук не пишется), поэтому оба
 * начинаются с 0 и идут вместе. Чистая логика, тесты — RecordingRulesTest.
 */
class SegmentTimeline(private val pauseGapUs: Long = PAUSE_GAP_US) {

    private var originUs = -1L
    private var lastInUs = -1L
    private var lastOutUs = -1L
    private var cutUs = 0L

    /** Время в файле (мкс) для кадра со временем потока [ptsUs]; [frameUs] — шаг кадра. */
    fun map(ptsUs: Long, frameUs: Long): Long {
        if (originUs < 0) {
            originUs = ptsUs
            lastInUs = ptsUs
            lastOutUs = 0
            return 0
        }
        val delta = ptsUs - lastInUs
        // Пауза или скачок часов назад — вырезаем, оставляя один шаг кадра.
        if (delta > pauseGapUs || delta <= 0) cutUs += delta - frameUs
        lastInUs = ptsUs
        lastOutUs = maxOf(ptsUs - originUs - cutUs, lastOutUs + 1)
        return lastOutUs
    }

    /** Длительность записанного (мкс). */
    val durationUs: Long get() = maxOf(lastOutUs, 0)

    companion object {
        /** Больше этого между кадрами — пауза потока (кадры при 15–30 к/с идут через 33–66 мс). */
        const val PAUSE_GAP_US = 1_000_000L
    }
}

/** Что делать, когда поток с очков прервался. */
enum class RecoveryAction {
    /** Ничего: поток продолжается сам (неопасная ошибка). */
    CONTINUE,

    /** Закрыть кусок, сказать причину и снова открыть сессию, когда можно. */
    RETRY,

    /** Закрыть кусок, сказать причину и остановить запись. */
    STOP,
}

/** Почему поток прервался — для голоса, экрана и решения. */
enum class StopReason {
    HINGE_CLOSED, THERMAL, BATTERY_LOW, PEAK_POWER, STREAM_FAILED, LINK_LOST, PERMISSION, LOW_STORAGE, SESSION_FAILED,
}

/**
 * Правила записи (чистая логика, тесты — RecordingRulesTest): что делать при каждой причине, через сколько
 * повторять, когда начинать новый кусок, когда предупреждать о месте.
 */
object RecordingRules {

    /** Кусок — 10 минут: при сбое теряется только он (MP4 без завершения не открыть). */
    const val SEGMENT_US = 10 * 60 * 1_000_000L

    /** Меньше — предупреждение на экране; меньше [STOP_FREE_BYTES] — запись останавливается. */
    const val WARN_FREE_BYTES = 1L * 1024 * 1024 * 1024
    const val STOP_FREE_BYTES = 300L * 1024 * 1024

    fun action(reason: StopReason): RecoveryAction = when (reason) {
        StopReason.BATTERY_LOW, StopReason.PERMISSION, StopReason.LOW_STORAGE -> RecoveryAction.STOP
        else -> RecoveryAction.RETRY
    }

    /** Пауза перед попыткой [attempt] (с 1): перегрев остывает дольше, остальное — 5, 10, 20… до минуты. */
    fun retryDelayMs(reason: StopReason, attempt: Int): Long {
        val base = when (reason) {
            StopReason.THERMAL -> 60_000L
            StopReason.PEAK_POWER -> 10_000L
            else -> 5_000L
        }
        return (base shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(60_000L).coerceAtLeast(base)
    }

    /** Новый кусок: на ключевом кадре — по времени или когда сменилось описание потока (очки снизили размер). */
    fun startNewSegment(isKey: Boolean, segmentUs: Long, parametersChanged: Boolean): Boolean =
        isKey && (parametersChanged || segmentUs >= SEGMENT_US)
}
