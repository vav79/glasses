package ru.vasev.glasses.video

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.glasses.DatErrors
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.speech.AudioChannel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Что сейчас с записью. */
enum class RecPhase { IDLE, STARTING, RECORDING, PAUSED, WAITING }

/** Состояние записи для экрана и уведомления. */
data class RecordingStatus(
    val phase: RecPhase = RecPhase.IDLE,
    val elapsedMs: Long = 0,
    val bytes: Long = 0,
    val segments: Int = 0,
    val reason: StopReason? = null,
    val audio: String? = null,
)

/**
 * Запись видео с камеры очков без ограничения по времени (на всё приложение, в [GlassesApp]; держит
 * [RecordingService]). HEVC с очков — в MP4 без перекодирования ([Mp4Segment]), кусками по 10 минут прямо в
 * галерею. Пауза касанием дужки вырезается ([SegmentTimeline]); не вернулась сама — «Продолжить» ([resume])
 * открывает новую сессию. Перегрев, сложенные дужки, обрыв связи — кусок закрывается, причина звучит голосом,
 * после восстановления — новый кусок ([RecordingRules]). Сессией с очками владеет запись: снимок «Что я вижу»
 * берётся из её потока (GlassesManager.recordingStream), запись не прерывается. Лог `Video`.
 */
class VideoRecorder(private val app: GlassesApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var resumeRequested = false
    private var session: DeviceSession? = null

    // Текущий кусок (кадры приходят в IO-потоке, звук — в своём).
    @Volatile
    private var segment: Mp4Segment? = null
    private var timeline = SegmentTimeline()
    private var parameterSets: ByteArray? = null
    private var lastSps: ByteArray? = null
    private var size = VideoSize.P720
    private var closedUs = 0L
    private var closedBytes = 0L
    private var segmentsDone = 0
    private var framesInSegment = 0

    @Volatile
    private var paused = false
    private var audio: AudioCapture? = null
    private var micHeld = false

    @Volatile
    private var audioFormat: MediaFormat? = null

    @Volatile
    private var segmentAudioBaseUs = 0L

    val isRecording: Boolean get() = job?.isActive == true

    /**
     * Начать запись (с экрана: служба стартует только из открытого приложения). null — началась,
     * иначе — строка с причиной, почему нет.
     */
    @StringRes
    fun start(): Int? {
        if (isRecording) return null
        val audioMode = app.videoSettings.audio.value
        if (audioMode != VideoAudio.NONE && !granted(Manifest.permission.RECORD_AUDIO)) return R.string.video_err_mic
        if (!app.glasses.status.value.ready) return R.string.video_err_no_glasses
        if (freeBytes() < RecordingRules.STOP_FREE_BYTES) return R.string.video_err_storage
        size = app.videoSettings.size.value
        closedUs = 0
        closedBytes = 0
        segmentsDone = 0
        _status.value = RecordingStatus(phase = RecPhase.STARTING)
        RecordingService.start(app, withMic = audioMode != VideoAudio.NONE)
        job = scope.launch { record(audioMode) }
        return null
    }

    /** Остановить запись: текущий кусок закрывается и появляется в галерее. */
    fun stop() {
        Log.d(TAG, "stop requested")
        job?.cancel()
    }

    /** «Продолжить» на паузе: приостановленную сессию закрыть и открыть новую (DAT не даёт перезапустить паузу). */
    fun resume() {
        if (_status.value.phase != RecPhase.PAUSED) return
        Log.d(TAG, "resume requested: reopening session")
        resumeRequested = true
        session?.let { runCatching { it.stop() } }
    }

    private suspend fun record(audioMode: VideoAudio) {
        try {
            startAudio(audioMode)
            var attempt = 0
            while (true) {
                val reason = runSession()
                closeSegment()
                if (reason == null) {
                    // «Продолжить» — сразу новая сессия.
                    attempt = 0
                    continue
                }
                Log.w(TAG, "stream ended: $reason")
                say(reasonText(reason))
                if (RecordingRules.action(reason) == RecoveryAction.STOP) break
                attempt++
                _status.update { it.copy(phase = RecPhase.WAITING, reason = reason) }
                delay(RecordingRules.retryDelayMs(reason, attempt))
                awaitGlassesLink()
                _status.update { it.copy(phase = RecPhase.STARTING) }
            }
        } finally {
            withContext(NonCancellable) {
                closeSegment()
                stopAudio()
                app.glasses.recordingStream = null
                session?.let { runCatching { it.stop() } }
                session = null
                RecordingService.stop(app)
                Log.d(TAG, "recording finished: segments=$segmentsDone duration=${closedUs / 1_000_000}s bytes=$closedBytes")
                _status.value = RecordingStatus(phase = RecPhase.IDLE, elapsedMs = closedUs / 1000, bytes = closedBytes, segments = segmentsDone)
                app.videoLibrary.refresh()
            }
        }
    }

    /** Одна сессия с очками; возвращает причину конца потока, null — «Продолжить» (новая сессия сразу). */
    private suspend fun runSession(): StopReason? {
        resumeRequested = false
        if (!awaitGlassesLink(timeoutMs = 20_000)) return StopReason.LINK_LOST
        val created = Wearables.createSession(AutoDeviceSelector())
        val s = created.getOrNull() ?: return StopReason.SESSION_FAILED.also { Log.w(TAG, "createSession: ${created.errorOrNull()}") }
        session = s
        try {
            return coroutineScope {
                val end = CompletableDeferred<StopReason?>()
                fun finish(reason: StopReason?) {
                    end.complete(if (resumeRequested) null else reason)
                }
                launch {
                    s.errors.collect { error ->
                        Log.w(TAG, "session error=$error")
                        if (DatErrors.session(error) != null) finish(StopReason.SESSION_FAILED)
                    }
                }
                var started = false
                launch {
                    s.state.collect { state ->
                        Log.d(TAG, "session state=$state")
                        when (state) {
                            DeviceSessionState.STARTED -> started = true
                            DeviceSessionState.PAUSED -> setPaused(true)
                            DeviceSessionState.STOPPED -> if (started) finish(StopReason.LINK_LOST)
                            else -> Unit
                        }
                    }
                }
                s.start()
                val ok = withTimeoutOrNull(15_000) { s.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
                if (ok != DeviceSessionState.STARTED) return@coroutineScope StopReason.SESSION_FAILED.also { coroutineContext.cancelChildren() }

                val config = StreamConfiguration(videoQuality = size.quality, frameRate = FPS, compressVideo = true)
                val camera = s.addCamera(config).getOrNull()
                    ?: return@coroutineScope StopReason.STREAM_FAILED.also { coroutineContext.cancelChildren() }
                val stream = camera.stream
                launch {
                    stream.errorStream.collect { error ->
                        Log.w(TAG, "stream error=$error")
                        streamReason(error)?.let(::finish)
                    }
                }
                var streamed = false
                launch {
                    stream.state.collect { state ->
                        Log.d(TAG, "stream state=$state")
                        when (state) {
                            StreamState.STREAMING -> {
                                streamed = true
                                app.glasses.recordingStream = stream
                                setPaused(false)
                            }
                            StreamState.PAUSED -> setPaused(true)
                            StreamState.STOPPED, StreamState.CLOSED -> if (streamed) finish(StopReason.STREAM_FAILED)
                            else -> Unit
                        }
                    }
                }
                launch(Dispatchers.IO) { stream.videoStream.collect(::onFrame) }
                launch {
                    while (isActive) {
                        delay(STORAGE_CHECK_MS)
                        if (freeBytes() < RecordingRules.STOP_FREE_BYTES) finish(StopReason.LOW_STORAGE)
                    }
                }
                stream.start().errorOrNull()?.let {
                    Log.w(TAG, "stream start failed: $it")
                    finish(StopReason.STREAM_FAILED)
                }
                val reason = end.await()
                coroutineContext.cancelChildren()
                reason
            }
        } finally {
            app.glasses.recordingStream = null
            runCatching { s.stop() }
            session = null
            setPaused(false)
        }
    }

    private fun onFrame(frame: VideoFrame) {
        if (frame.isCodecConfig) return
        val data = ByteArray(frame.buffer.remaining()).also { frame.buffer.duplicate().get(it) }
        val key = Hevc.isKeyFrame(data)
        var changed = false
        if (key) {
            val sets = Hevc.parameterSets(data)
            if (sets.isNotEmpty()) {
                // Новый кусок — только если сменился SPS (там размер кадра): после снимка «Что я вижу» очки меняют
                // другие части описания, и файл делился зря (проверено 2026-11-03).
                val sps = Hevc.unit(data, Hevc.SPS)
                changed = sps != null && lastSps != null && !sps.contentEquals(lastSps)
                if (parameterSets?.contentEquals(sets) == false && !changed) Log.d(TAG, "parameter sets changed, SPS same — same segment")
                if (sps != null) lastSps = sps
                parameterSets = sets
            }
        }
        try {
            if (segment == null) {
                if (!key || parameterSets == null) return
                openSegment()
            } else if (RecordingRules.startNewSegment(key, timeline.durationUs, changed)) {
                Log.d(TAG, "segment rollover: ${if (changed) "stream parameters changed" else "10 min"}")
                closeSegment()
                openSegment()
            }
        } catch (e: Exception) {
            // Галерея не дала файл — попробуем на следующем ключевом кадре.
            Log.w(TAG, "segment open failed: ${e.javaClass.simpleName}")
            return
        }
        val seg = segment ?: return
        val pts = timeline.map(frame.presentationTimeUs, FRAME_US)
        runCatching { seg.writeVideo(data, pts, key) }.onFailure { Log.w(TAG, "write failed: ${it.javaClass.simpleName}") }
        if (++framesInSegment % STATUS_EVERY_FRAMES == 0) publish()
    }

    @Synchronized
    private fun openSegment() {
        val name = "Sputnik_${LocalDateTime.now().format(NAME_FORMAT)}.mp4"
        val seg = Mp4Segment(app.contentResolver, name, withAudio = audio != null)
        seg.setVideo(parameterSets!!, size.width, size.height)
        audioFormat?.let(seg::setAudio)
        timeline = SegmentTimeline()
        framesInSegment = 0
        segmentAudioBaseUs = audio?.nextPtsUs ?: 0
        segment = seg
        audio?.feeding = !paused
        if (audio != null && audioFormat == null) {
            scope.launch {
                delay(AUDIO_FORMAT_WAIT_MS)
                if (segment === seg) seg.startWithoutAudio()
            }
        }
        Log.d(TAG, "segment opened: $name ${size.width}x${size.height} audio=${audio != null}")
        publish()
    }

    @Synchronized
    private fun closeSegment() {
        val seg = segment ?: return
        segment = null
        audio?.feeding = false
        val uri = seg.close()
        closedUs += timeline.durationUs
        closedBytes += seg.bytes
        if (uri != null) segmentsDone++
        Log.d(TAG, "segment closed: saved=${uri != null} duration=${timeline.durationUs / 1_000_000}s kb=${seg.bytes / 1024}")
        publish()
    }

    private fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        audio?.feeding = !value && segment != null
        Log.d(TAG, if (value) "paused (tap on glasses)" else "resumed")
        _status.update { it.copy(phase = if (value) RecPhase.PAUSED else RecPhase.RECORDING, reason = null) }
    }

    private fun publish() {
        val current = segment
        _status.update {
            it.copy(
                phase = if (paused) RecPhase.PAUSED else if (current != null) RecPhase.RECORDING else it.phase,
                elapsedMs = (closedUs + if (current != null) timeline.durationUs else 0) / 1000,
                bytes = closedBytes + (current?.bytes ?: 0),
                segments = segmentsDone + if (current != null) 1 else 0,
                reason = if (current != null) null else it.reason,
            )
        }
    }

    /** Звук: «Авто» — микрофон очков или гарнитуры, если подключены (удерживаем на всю запись), иначе телефон. */
    private suspend fun startAudio(mode: VideoAudio) {
        if (mode == VideoAudio.NONE) return
        val headsetMic = if (mode == VideoAudio.AUTO) app.audioRouter.micHeadset()?.mic else null
        micHeld = headsetMic != null && app.audioRouter.holdMic()
        val capture = AudioCapture(
            device = if (micHeld) headsetMic else null,
            onFormat = { format ->
                audioFormat = format
                segment?.setAudio(format)
            },
            onSample = { data, ptsUs ->
                val seg = segment
                val rel = ptsUs - segmentAudioBaseUs
                if (seg != null && rel >= 0) runCatching { seg.writeAudio(data, rel) }
            },
        )
        runCatching { capture.start() }.onFailure {
            Log.w(TAG, "audio: start failed ${it.javaClass.simpleName}, recording without sound")
            if (micHeld) app.audioRouter.unholdMic()
            micHeld = false
            return
        }
        audio = capture
        _status.update { it.copy(audio = capture.describe) }
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
        audioFormat = null
        if (micHeld) app.audioRouter.unholdMic()
        micHeld = false
    }

    /** Ждёт, пока очки на связи; false — не дождались за [timeoutMs] (по умолчанию — сколько угодно). */
    private suspend fun awaitGlassesLink(timeoutMs: Long = Long.MAX_VALUE): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!glassesLinked()) delay(2_000)
            true
        } ?: false

    private suspend fun glassesLinked(): Boolean =
        Wearables.devices.value.any { id -> Wearables.devicesMetadata[id]?.first()?.linkState == LinkState.CONNECTED }

    private fun streamReason(error: StreamError): StopReason? = when (error) {
        StreamError.HINGE_CLOSED -> StopReason.HINGE_CLOSED
        StreamError.THERMAL_HOT -> StopReason.THERMAL
        StreamError.BATTERY_LOW -> StopReason.BATTERY_LOW
        StreamError.PEAK_POWER_LIMIT -> StopReason.PEAK_POWER
        StreamError.PERMISSIONS_DENIED -> StopReason.PERMISSION
        StreamError.CRITICAL_STREAM_ERROR, StreamError.TIMEOUT -> StopReason.STREAM_FAILED
        else -> null // STREAM_ERROR: поток продолжается
    }

    private fun reasonText(reason: StopReason): Int = when (reason) {
        StopReason.HINGE_CLOSED -> R.string.video_reason_hinge
        StopReason.THERMAL -> R.string.video_reason_thermal
        StopReason.BATTERY_LOW -> R.string.video_reason_battery
        StopReason.PEAK_POWER -> R.string.video_reason_peak
        StopReason.STREAM_FAILED -> R.string.video_reason_stream
        StopReason.LINK_LOST -> R.string.video_reason_link
        StopReason.PERMISSION -> R.string.video_reason_permission
        StopReason.LOW_STORAGE -> R.string.video_reason_storage
        StopReason.SESSION_FAILED -> R.string.video_reason_session
    }

    /** Причина — голосом: в очки, если они на связи, иначе в динамик телефона (канал ME сам выбирает). */
    private fun say(@StringRes text: Int) {
        scope.launch {
            runCatching { app.audioOutput.speak(L.s(text), LanguageSettings.current.value.locale, AudioChannel.ME) }
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** Свободно на общей памяти телефона (галерея). */
    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    companion object {
        private const val TAG = "Video"
        const val FPS = 30
        private const val FRAME_US = 1_000_000L / FPS
        private const val STATUS_EVERY_FRAMES = 15
        private const val STORAGE_CHECK_MS = 30_000L
        private const val AUDIO_FORMAT_WAIT_MS = 2_000L
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
