package ru.vasev.glasses.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Лёгкая вибрация в начале и в конце прослушивания — чтобы понимать, что микрофон включён, не глядя на экран. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Микрофон включился — один короткий щелчок. */
    fun listenStart() = play(VibrationEffect.EFFECT_CLICK)

    /** Микрофон выключился — двойной щелчок, чтобы отличался от начала. */
    fun listenEnd() = play(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun play(effect: Int) {
        val v = vibrator ?: return
        if (v.hasVibrator()) runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }
}
