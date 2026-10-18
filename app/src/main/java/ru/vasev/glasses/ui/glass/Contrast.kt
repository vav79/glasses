package ru.vasev.glasses.ui.glass

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Контраст по WCAG 2.x для «стекла»: текст лежит на полупрозрачной подложке, а под ней — что угодно
 * из [Rgb]-набора «что может оказаться позади» (цвета фона, пузыри, размытый текст).
 * Плотность подложки не подбирается на глаз — [minTintAlpha] находит наименьшую, при которой
 * контраст не ниже цели против каждого возможного фона. Чистый Kotlin — под unit-тестами.
 */
object Contrast {

    /** Цель WCAG AA для обычного текста. */
    const val AA = 4.5

    /** Цвет sRGB 0..1 без альфы. */
    data class Rgb(val r: Double, val g: Double, val b: Double) {
        companion object {
            fun of(argb: Long): Rgb = Rgb(
                ((argb shr 16) and 0xFF) / 255.0,
                ((argb shr 8) and 0xFF) / 255.0,
                (argb and 0xFF) / 255.0,
            )

            fun of(argb: Int): Rgb = of(argb.toLong() and 0xFFFFFFFFL)
        }
    }

    /** Относительная яркость WCAG. */
    fun luminance(c: Rgb): Double {
        fun channel(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
    }

    fun ratio(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Подложка [tint] с непрозрачностью [alpha] поверх [behind] (обычное наложение в sRGB, как у Compose). */
    fun over(tint: Rgb, alpha: Double, behind: Rgb): Rgb = Rgb(
        tint.r * alpha + behind.r * (1 - alpha),
        tint.g * alpha + behind.g * (1 - alpha),
        tint.b * alpha + behind.b * (1 - alpha),
    )

    /** Худший контраст текста на подложке против всех возможных фонов. */
    fun worst(text: Rgb, tint: Rgb, alpha: Double, behind: Collection<Rgb>): Double =
        behind.minOf { ratio(text, over(tint, alpha, it)) }

    /**
     * Наименьшая непрозрачность подложки (шаг 0.01, не ниже [floor]), при которой контраст текста
     * не ниже [target] против любого из [behind]. 1.0 — если цели не достичь даже сплошной подложкой
     * (тогда виноват сам цвет текста, а не прозрачность).
     */
    /**
     * Акцентный цвет текста (например, `primary` из палитры обоев) бывает недостаточно контрастным даже
     * на сплошной подложке. Возвращает наименьшую долю смешения [text] → [toward] (обычно к onSurface),
     * при которой контраст на подложке [tint] с [alpha] не ниже [target]; 0 — цвет и так подходит.
     */
    fun minMixToward(
        text: Rgb,
        toward: Rgb,
        tint: Rgb,
        alpha: Double,
        behind: Collection<Rgb>,
        target: Double = AA,
    ): Double {
        for (step in 0..100) {
            val k = step / 100.0
            val mixed = Rgb(text.r + (toward.r - text.r) * k, text.g + (toward.g - text.g) * k, text.b + (toward.b - text.b) * k)
            if (worst(mixed, tint, alpha, behind) >= target) return k
        }
        return 1.0
    }

    fun minTintAlpha(text: Rgb, tint: Rgb, behind: Collection<Rgb>, target: Double = AA, floor: Double = 0.0): Double {
        var step = (floor * 100).toInt().coerceIn(0, 100)
        while (step <= 100) {
            val alpha = step / 100.0
            if (worst(text, tint, alpha, behind) >= target) return alpha
            step++
        }
        return 1.0
    }
}
