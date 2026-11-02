package ru.vasev.glasses.video

import java.io.ByteArrayOutputStream

/**
 * Разбор HEVC-кадров, которые присылают очки (compressVideo = true): буфер с начальными кодами 00 00 01 /
 * 00 00 00 01. Проверено пробой (debug.VideoProbeActivity, 2026-11-02): отдельных кадров с описанием потока
 * нет — VPS, SPS и PPS (типы NAL 32–34) лежат в самом ключевом кадре перед IDR (19); ключевой кадр — раз в 3 с.
 * Чистая логика, тесты — HevcTest.
 */
object Hevc {

    private const val VPS = 32
    private const val PPS = 34

    /** Начала NAL-блоков: индекс начального кода и его длина (3 или 4). */
    private fun starts(data: ByteArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                out += if (i > 0 && data[i - 1] == 0.toByte()) (i - 1) to 4 else i to 3
                i += 3
            } else {
                i++
            }
        }
        return out
    }

    /** Типы NAL-блоков по порядку. */
    fun nalTypes(data: ByteArray): List<Int> = starts(data).mapNotNull { (at, len) ->
        data.getOrNull(at + len)?.let { (it.toInt() shr 1) and 0x3F }
    }

    /** NAL-блок нужного типа (с начальным кодом) или null — например, SPS (33), где записан размер кадра. */
    fun unit(data: ByteArray, type: Int): ByteArray? {
        val starts = starts(data)
        starts.forEachIndexed { k, (at, len) ->
            val t = data.getOrNull(at + len)?.let { (it.toInt() shr 1) and 0x3F } ?: return@forEachIndexed
            if (t == type) return data.copyOfRange(at + len, if (k + 1 < starts.size) starts[k + 1].first else data.size)
        }
        return null
    }

    const val SPS = 33

    /** Ключевой кадр — IRAP (BLA, IDR, CRA: типы 16–21): с него можно начинать файл. */
    fun isKeyFrame(data: ByteArray): Boolean = nalTypes(data).any { it in 16..21 }

    /** VPS, SPS и PPS вместе с начальными кодами — csd-0 для MediaMuxer; пусто, если их в кадре нет. */
    fun parameterSets(data: ByteArray): ByteArray {
        val starts = starts(data)
        val out = ByteArrayOutputStream()
        starts.forEachIndexed { k, (at, len) ->
            val type = data.getOrNull(at + len)?.let { (it.toInt() shr 1) and 0x3F } ?: return@forEachIndexed
            val end = if (k + 1 < starts.size) starts[k + 1].first else data.size
            if (type in VPS..PPS) out.write(data, at, end - at)
        }
        return out.toByteArray()
    }
}
