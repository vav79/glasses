package ru.vasev.glasses.debug

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.LinkState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.ui.theme.GlassesTheme
import java.io.File
import java.nio.ByteBuffer

/**
 * Проба видео с очков (только debug): HEVC без перекодирования (compressVideo) → MP4 через MediaMuxer.
 * adb shell am start -n ru.vasev.glasses/.debug.VideoProbeActivity [--es quality high|medium|low] [--ei fps 30]
 *   [--ez audio true] [--ei seconds 60] [--ez autostart true]
 * Лог VideoProbe: состояния, ошибки, раз в 5 с — кадры, байты, ключевые кадры, разрывы; в конце — итог и путь к файлу
 * (files/Movies на «внешней» памяти приложения — adb pull без root).
 * Что выясняем: играется ли MP4 без перекодирования, битрейт, интервал ключевых кадров, что после паузы касанием,
 * меняется ли описание потока (SPS) посреди записи, идут ли кадры при погасшем экране, приходит ли звук DAT.
 */
class VideoProbeActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private var session: DeviceSession? = null
    private val jobs = mutableListOf<Job>()
    private val startedAt = SystemClock.elapsedRealtime()

    // Статистика текущей записи.
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var file: File? = null
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var frames = 0
    private var bytes = 0L
    private var keyFrames = 0
    private var lastKeyPtsUs = -1L
    private val keyIntervalsMs = mutableListOf<Long>()
    private var configFrames = 0
    private var configChanges = 0
    private var config: ByteArray? = null
    private var gaps = 0
    private var maxGapMs = 0L
    private var droppedBeforeKey = 0
    private var audioFrames = 0
    private var audioBytes = 0L
    private var lastAudioPtsUs = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Экран не гаснет, пока не нажата «Allow screen off» (проверка записи с погасшим экраном).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            GlassesTheme(dark = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Glasses video probe", style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { start(VideoQuality.HIGH, 30, audio = false, seconds = 60) }) { Text("720p 30") }
                            Button(onClick = { start(VideoQuality.MEDIUM, 30, audio = false, seconds = 60) }) { Text("504p 30") }
                            Button(onClick = { start(VideoQuality.HIGH, 30, audio = true, seconds = 60) }) { Text("720p + DAT audio") }
                            Button(onClick = ::stop) { Text("Stop") }
                            Button(onClick = { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }) { Text("Allow screen off") }
                        }
                        val listState = rememberLazyListState()
                        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(lines) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        log("registration=${Wearables.registrationState.value} devMode=${Wearables.isDevMode}")
        if (intent.getBooleanExtra("autostart", false)) {
            val quality = when (intent.getStringExtra("quality")) { "medium" -> VideoQuality.MEDIUM; "low" -> VideoQuality.LOW; else -> VideoQuality.HIGH }
            start(quality, intent.getIntExtra("fps", 30), intent.getBooleanExtra("audio", false), intent.getIntExtra("seconds", 60))
        }
    }

    private fun start(quality: VideoQuality, fps: Int, audio: Boolean, seconds: Int) {
        if (session != null) return log("already running")
        reset()
        lifecycleScope.launch {
            // Сразу после старта процесса DAT ещё не знает очков (NO_ELIGIBLE_DEVICE) — ждём, пока появятся.
            val devices = withTimeoutOrNull(15_000) { Wearables.devices.first { it.isNotEmpty() } }
            log("devices=${devices?.size ?: 0}")
            val id = devices?.firstOrNull()
            val linked = id?.let { d -> withTimeoutOrNull(20_000) { Wearables.devicesMetadata[d]?.first { it.linkState == LinkState.CONNECTED } } }
            log("link=${linked?.linkState}")
            val created = Wearables.createSession(AutoDeviceSelector())
            val s = created.getOrNull() ?: return@launch log("createSession failed: ${created.errorOrNull()}")
            session = s
            jobs += launch { s.errors.collect { log("session error=$it") } }
            jobs += launch { s.state.collect { log("session state=$it") } }
            s.start()
            val state = withTimeoutOrNull(15_000) { s.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
            if (state != DeviceSessionState.STARTED) return@launch log("session not started: $state").also { stop() }
            val config = StreamConfiguration(
                audioCodec = if (audio) AudioCodec.PCM(sampleRate = AudioSampleRate.RATE_16000, numberOfChannels = 1) else null,
                videoQuality = quality,
                frameRate = fps,
                compressVideo = true,
            )
            val added = s.addCamera(config)
            val camera = added.getOrNull() ?: return@launch log("addCamera failed: ${added.errorOrNull()}").also { stop() }
            val stream = camera.stream
            log("stream config quality=$quality fps=$fps audio=$audio compress=true")
            jobs += launch { stream.state.collect { log("stream state=$it") } }
            jobs += launch { stream.errorStream.collect { log("stream error=$it") } }
            jobs += launch { stream.videoStream.collect(::onFrame) }
            if (audio) {
                jobs += launch {
                    stream.audioStream.collect { frame ->
                        audioFrames++
                        audioBytes += frame.buffer.remaining()
                        lastAudioPtsUs = frame.presentationTimeUs
                        if (audioFrames == 1) log("audio first frame bytes=${frame.buffer.remaining()} pts=${frame.presentationTimeUs}")
                    }
                }
            }
            jobs += launch {
                var tick = 0
                while (isActive) {
                    delay(5_000)
                    tick += 5
                    log("t=${tick}s frames=$frames kb=${bytes / 1024} key=$keyFrames config=$configFrames/${configChanges} gaps=$gaps maxGap=${maxGapMs}ms audio=$audioFrames/${audioBytes / 1024}kb")
                    if (tick >= seconds) {
                        stop()
                        break
                    }
                }
            }
            stream.start().errorOrNull()?.let { log("stream start failed: $it") }
        }
    }

    private fun onFrame(frame: VideoFrame) {
        val data = ByteArray(frame.buffer.remaining()).also { frame.buffer.duplicate().get(it) }
        if (frame.isCodecConfig) {
            configFrames++
            if (config != null && !config!!.contentEquals(data)) {
                configChanges++
                log("codec config CHANGED (frame ${frames}) — new segment needed; size field ${frame.width}x${frame.height}")
            }
            if (config == null) log("codec config bytes=${data.size} nal=${nalTypes(data)} size field ${frame.width}x${frame.height}")
            config = data
            return
        }
        val key = isKeyFrame(data)
        // DAT кладёт описание потока (VPS/SPS/PPS) в сам ключевой кадр, отдельных config-кадров нет.
        if (key) {
            val sets = parameterSets(data)
            if (sets.isNotEmpty()) {
                if (config == null) log("parameter sets in key frame bytes=${sets.size}")
                else if (!config!!.contentEquals(sets)) {
                    configChanges++
                    log("parameter sets CHANGED at frame $frames — new segment needed")
                }
                config = sets
            }
        }
        if (muxer == null) {
            val csd = config
            if (csd == null || !key) {
                droppedBeforeKey++
                if (droppedBeforeKey == 1) log("first frame before config/key: key=$key nal=${nalTypes(data)} compressed=${frame.isCompressed}")
                return
            }
            openMuxer(csd, frame.width, frame.height)
        }
        val pts = frame.presentationTimeUs
        if (firstPtsUs < 0) firstPtsUs = pts
        if (lastPtsUs >= 0) {
            val gapMs = (pts - lastPtsUs) / 1000
            if (gapMs > 200) {
                gaps++
                log("gap ${gapMs}ms before frame $frames")
            }
            if (gapMs > maxGapMs) maxGapMs = gapMs
            if (pts <= lastPtsUs) log("pts not increasing: $pts <= $lastPtsUs")
        }
        lastPtsUs = pts
        if (key) {
            if (lastKeyPtsUs >= 0) keyIntervalsMs += (pts - lastKeyPtsUs) / 1000
            lastKeyPtsUs = pts
            keyFrames++
        }
        frames++
        bytes += data.size
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, (pts - firstPtsUs).coerceAtLeast(0), if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        runCatching { muxer!!.writeSampleData(track, ByteBuffer.wrap(data), info) }.onFailure { log("write failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun openMuxer(csd: ByteArray, width: Int, height: Int) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.apply { mkdirs() }
        val f = File(dir, "probe_${System.currentTimeMillis()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        val m = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        track = m.addTrack(format)
        m.start()
        muxer = m
        file = f
        log("muxer started ${f.name} ${width}x$height (frames before first key dropped: $droppedBeforeKey)")
    }

    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.let { runCatching { it.stop() } }
        session = null
        val m = muxer
        muxer = null
        if (m != null) {
            runCatching { m.stop(); m.release() }.onFailure { log("muxer stop failed: ${it.message}") }
        }
        val durationS = if (firstPtsUs >= 0) (lastPtsUs - firstPtsUs) / 1_000_000.0 else 0.0
        val kbps = if (durationS > 0) bytes * 8 / 1000 / durationS else 0.0
        val keyAvg = if (keyIntervalsMs.isNotEmpty()) keyIntervalsMs.average().toLong() else -1
        log("DONE file=${file?.name} size_kb=${(file?.length() ?: 0) / 1024} duration=${"%.1f".format(durationS)}s frames=$frames fps=${"%.1f".format(if (durationS > 0) frames / durationS else 0.0)} kbps=${"%.0f".format(kbps)} key=$keyFrames key_interval_avg=${keyAvg}ms config_changes=$configChanges gaps=$gaps maxGap=${maxGapMs}ms audio_frames=$audioFrames audio_kb=${audioBytes / 1024}")
        log("pull: adb pull ${file?.path}")
    }

    private fun reset() {
        track = -1; file = null; firstPtsUs = -1; lastPtsUs = -1; frames = 0; bytes = 0; keyFrames = 0; lastKeyPtsUs = -1
        keyIntervalsMs.clear(); configFrames = 0; configChanges = 0; config = null; gaps = 0; maxGapMs = 0; droppedBeforeKey = 0
        audioFrames = 0; audioBytes = 0; lastAudioPtsUs = -1
    }

    /** Типы NAL-блоков HEVC в буфере с начальными кодами 00 00 01 / 00 00 00 01. */
    private fun nalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size && types.size < 8) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                types += (data[i + 3].toInt() shr 1) and 0x3F
                i += 3
            } else {
                i++
            }
        }
        return types
    }

    /** VPS, SPS и PPS (типы NAL 32–34) из буфера — вместе с начальными кодами, как csd-0 для MediaMuxer. */
    private fun parameterSets(data: ByteArray): ByteArray {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts += if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                i += 3
            } else {
                i++
            }
        }
        val out = java.io.ByteArrayOutputStream()
        starts.forEachIndexed { k, s ->
            val codeLen = if (data[s + 2] == 1.toByte()) 3 else 4
            val type = (data[s + codeLen].toInt() shr 1) and 0x3F
            val end = if (k + 1 < starts.size) starts[k + 1] else data.size
            if (type in 32..34) out.write(data, s, end - s)
        }
        return out.toByteArray()
    }

    /** Ключевой кадр HEVC — IRAP (типы NAL 16–21: BLA, IDR, CRA). */
    private fun isKeyFrame(data: ByteArray): Boolean = nalTypes(data).any { it in 16..21 }

    private fun log(text: String) {
        val line = "${SystemClock.elapsedRealtime() - startedAt}ms $text"
        Log.d(TAG, line)
        runOnUiThread { lines += line }
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "VideoProbe"
    }
}
