package ru.vasev.glasses.nav

/**
 * Plus Code (Open Location Code, 10 знаков ≈ 14 × 14 м) — считается по координатам без сети; его понимают
 * Google Карты и Grab. Алгоритм — по спецификации google/open-location-code. Чистая логика, тесты.
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val PAIRS = 5

    fun encode(point: GeoPoint): String {
        // Сдвиг в положительные числа и номер самой мелкой ячейки (1/8000°) — вниз, как в спецификации.
        var lat = Math.floor((point.latitude.coerceIn(-90.0, 90.0) + 90.0) * PRECISION).toLong()
        var lon = Math.floor((normalizeLongitude(point.longitude) + 180.0) * PRECISION).toLong()
        // Северный полюс — в последнюю ячейку, а не за край сетки.
        if (lat >= 180L * PRECISION) lat = 180L * PRECISION - 1
        if (lon >= 360L * PRECISION) lon = 0
        val digits = CharArray(PAIRS * 2)
        for (i in PAIRS - 1 downTo 0) {
            digits[i * 2 + 1] = ALPHABET[(lon % 20).toInt()]
            digits[i * 2] = ALPHABET[(lat % 20).toInt()]
            lat /= 20
            lon /= 20
        }
        val code = String(digits)
        return code.substring(0, 8) + "+" + code.substring(8)
    }

    private fun normalizeLongitude(lon: Double): Double {
        var l = lon
        while (l < -180) l += 360
        while (l >= 180) l -= 360
        return l
    }

    /** Пять пар знаков: шаги 20°, 1°, 1/20°, 1/400°, 1/8000° — самая мелкая ячейка 1/8000°. */
    private const val PRECISION = 8_000L
}
