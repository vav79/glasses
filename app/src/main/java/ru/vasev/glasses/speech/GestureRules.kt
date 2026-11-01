package ru.vasev.glasses.speech

import android.view.KeyEvent

/** Касание дужки очков — так, как оно приходит медиакнопкой Bluetooth (AVRCP). */
enum class Tap {
    /** Одно касание: `PAUSE` (очки думают, что звук идёт) или `PLAY`. */
    SINGLE,

    /** Двойное касание: «следующий трек». */
    DOUBLE,
    ;

    companion object {
        fun of(keyCode: Int): Tap? = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP,
            -> SINGLE
            KeyEvent.KEYCODE_MEDIA_NEXT -> DOUBLE
            else -> null
        }
    }
}

/** Что делает жест: [ASK] — начать вопрос, [INTERRUPT] — перебить ответ, [IGNORE] — ничего. */
enum class GestureAction { ASK, INTERRUPT, IGNORE }

/** Почему жесты сейчас не ловятся (или [READY] — ловятся). Для подписи в настройках, уведомления и лога. */
enum class GestureState { OFF, NO_GLASSES, OTHER_MEDIA, MEDIA_COOLDOWN, CALL, READY }

/**
 * Правила жестов на дужке (чистая логика, тесты — GestureRulesTest). Проверено на Ray-Ban Meta Gen 2:
 * DAT Inputs касаний не отдаёт, а медиакнопки Bluetooth приходят приложению, которое последним играло звук.
 */
object GestureRules {

    /** Повтор раньше этого — то же касание: быстрое касание давало `PAUSE` и через 0,5 с `PLAY`. */
    const val DEBOUNCE_MS = 1_500L

    /** После остановки чужой музыки ждём: иначе касание «продолжить музыку» досталось бы нам. */
    const val MEDIA_COOLDOWN_MS = 60_000L

    /**
     * Во время ответа перебивает любое касание. В покое вопрос начинает только выбранный жест [chosen]
     * (null — жесты выключены): очки весь день на лице, их поправляют, одиночное касание бывает случайным.
     */
    fun action(tap: Tap, answering: Boolean, chosen: Tap?): GestureAction = when {
        answering -> GestureAction.INTERRUPT
        chosen != null && tap == chosen -> GestureAction.ASK
        else -> GestureAction.IGNORE
    }

    /**
     * Ловить ли касания в покое. Чужая музыка играет — нет: касание должно оставаться её паузой.
     * [otherMediaEndedAt] — когда она замолчала (elapsedRealtime), null — не играла.
     */
    fun state(
        chosen: Tap?,
        glassesConnected: Boolean,
        otherMediaPlaying: Boolean,
        otherMediaEndedAt: Long?,
        inCall: Boolean,
        now: Long,
    ): GestureState = when {
        chosen == null -> GestureState.OFF
        !glassesConnected -> GestureState.NO_GLASSES
        inCall -> GestureState.CALL
        otherMediaPlaying -> GestureState.OTHER_MEDIA
        otherMediaEndedAt != null && now - otherMediaEndedAt < MEDIA_COOLDOWN_MS -> GestureState.MEDIA_COOLDOWN
        else -> GestureState.READY
    }

    /** Антидребезг: принять касание, если с прошлого принятого прошло не меньше [DEBOUNCE_MS]. */
    fun accept(lastAcceptedAt: Long?, now: Long): Boolean = lastAcceptedAt == null || now - lastAcceptedAt >= DEBOUNCE_MS

    /** Два одиночных касания не дальше этого — одно двойное. */
    const val PAIR_MS = 800L

    /**
     * Двойное касание очки шлют не всегда `NEXT`: после паузы чужой музыки оно пришло парой `PAUSE` + `PLAY`
     * через 0,5 с (проверено на очках). Второе одиночное касание вслед за первым, которое ничего не сделало
     * ([previousIgnored]), — это двойное. Первое, которое что-то сделало (перебило ответ), не склеиваем.
     */
    fun pairsIntoDouble(previous: Tap?, previousAt: Long?, previousIgnored: Boolean, tap: Tap, now: Long): Boolean =
        tap == Tap.SINGLE && previous == Tap.SINGLE && previousIgnored && previousAt != null && now - previousAt <= PAIR_MS
}
