package ru.vasev.glasses.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Касания дужки очков и кнопка гарнитуры. Android отдаёт медиакнопки сессии приложения, которое последним
 * само играло звук, поэтому, пока мы ловим касания, в процессе крутится беззвучная дорожка (речь TTS играет
 * в процессе движка синтеза, не в нашем).
 *
 * - Во время ответа ([answering]) — любое касание или кнопка гарнитуры перебивает ответ (как раньше).
 * - В покое — только если включён «Жест на дужке», подключены очки, не играет чужая музыка (и минуту после неё)
 *   и нет звонка ([GestureRules.state]): выбранный жест начинает вопрос ([onAsk]).
 * - Очки шлют `PAUSE`/`PLAY` по тому, что, по их мнению, играет: после каждого касания тишина останавливается
 *   и через [REARM_MS] включается снова — иначе следующие касания до телефона не доходят (проверено на очках).
 *
 * Вызывать на главном потоке (колбэки — на нём же).
 */
class GlassesGestures(
    context: Context,
    private val router: AudioRouter,
    private val settings: GestureSettings,
    /** Наш звук собеседнику (MediaPlayer с USAGE_MEDIA) — его не считаем чужой музыкой. */
    private val ownMediaPlaying: () -> Boolean,
) {

    private val appContext = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Вопрос жестом. Задаёт MainActivity (пока она есть); null — экрана нет, тогда [onNoScreen]. */
    var onAsk: (() -> Unit)? = null

    /** Жест есть, а экрана нет (приложение закрыто «Назад» или смахнуто): вопрос задаёт ассистент в службе (GlassesApp). */
    var onNoScreen: (() -> Unit)? = null

    private val _state = MutableStateFlow(GestureState.OFF)

    /** Ловятся ли касания в покое — для настроек и уведомления. */
    val state: StateFlow<GestureState> = _state.asStateFlow()

    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private var answerPress: (() -> Unit)? = null
    private var lastAcceptedAt: Long? = null
    private var lastTap: Tap? = null
    private var lastIgnored = false
    private var otherMediaPlaying = false
    private var otherMediaEndedAt: Long? = null
    private var callActive = false
    private val rearm = Runnable { if (session != null) play() }
    private val recheck = Runnable { update() }

    init {
        audio.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onPlayback(configs)
            },
            main,
        )
        onPlayback(audio.activePlaybackConfigurations)
        // Звонок закончился, а новых воспроизведений нет — без этого жесты оставались выключенными (проверено на очках).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnModeChangedListener(appContext.mainExecutor) { update() }
        }
        scope.launch {
            combine(settings.enabled, settings.tap, router.headsets) { _, _, _ -> }.collect { update() }
        }
    }

    /** Ответ ассистента звучит: любое касание или кнопка гарнитуры — [onPress] (перебить). */
    fun answering(onPress: () -> Unit) {
        answerPress = onPress
        update()
    }

    /** Ответ закончился (или его перебили). */
    fun answerDone() {
        answerPress = null
        update()
    }

    private fun onPlayback(configs: List<AudioPlaybackConfiguration>) {
        // Свои воспроизведения: речь и тишина — USAGE_ASSISTANT; звук собеседнику — USAGE_MEDIA, его вычитаем.
        val media = configs.count { it.audioAttributes.usage in MEDIA_USAGES } - (if (ownMediaPlaying()) 1 else 0)
        val playing = media > 0
        val call = configs.any { it.audioAttributes.usage in CALL_USAGES }
        if (playing != otherMediaPlaying) {
            if (!playing) otherMediaEndedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "gesture: other media ${if (playing) "started" else "stopped"}")
        }
        otherMediaPlaying = playing
        callActive = call
        update()
    }

    private fun update() {
        main.removeCallbacks(recheck)
        val glasses = router.headsets.value.any { it.glasses && it.output != null }
        val inCall = callActive || audio.mode == AudioManager.MODE_IN_CALL
        val now = SystemClock.elapsedRealtime()
        val state = GestureRules.state(settings.chosen, glasses, otherMediaPlaying, otherMediaEndedAt, inCall, now)
        if (state != _state.value) Log.d(TAG, "gesture: state=$state")
        _state.value = state
        if (state == GestureState.MEDIA_COOLDOWN) {
            main.postDelayed(recheck, GestureRules.MEDIA_COOLDOWN_MS - (now - (otherMediaEndedAt ?: now)) + 100)
        }
        // Конец звонка виден не всегда (Android 10–11 — без слушателя режима): перепроверяем, пока он идёт.
        if (state == GestureState.CALL) main.postDelayed(recheck, CALL_RECHECK_MS)
        // Ответ звучит — ловим всегда (кнопка гарнитуры перебивает и с выключенными жестами); в покое — только готовые.
        val wanted = answerPress != null || state == GestureState.READY
        if (wanted && session == null) start() else if (!wanted && session != null) release()
    }

    private fun start() {
        session = MediaSession(appContext, "GlassesGestures").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = keyEvent(mediaButtonIntent) ?: return false
                    val tap = Tap.of(event.keyCode) ?: return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) press(tap, "key ${KeyEvent.keyCodeToString(event.keyCode)}")
                    return true
                }

                // Некоторые гарнитуры и Bluetooth-стек присылают команды транспорта, а не коды клавиш.
                override fun onPause() = press(Tap.SINGLE, "pause")
                override fun onPlay() = press(Tap.SINGLE, "play")
                override fun onStop() = press(Tap.SINGLE, "stop")
                override fun onSkipToNext() = press(Tap.DOUBLE, "next")
            })
            isActive = true
        }
        play()
        Log.d(TAG, "gesture: listening for taps (answering=${answerPress != null})")
    }

    private fun release() {
        main.removeCallbacks(rearm)
        stopSilence()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        Log.d(TAG, "gesture: stopped listening for taps")
    }

    private fun press(raw: Tap, what: String) {
        val now = SystemClock.elapsedRealtime()
        val paired = GestureRules.pairsIntoDouble(lastTap, lastAcceptedAt, lastIgnored, raw, now)
        if (!paired && !GestureRules.accept(lastAcceptedAt, now)) {
            Log.d(TAG, "gesture: $what ignored (repeat within ${GestureRules.DEBOUNCE_MS} ms)")
            return
        }
        val tap = if (paired) Tap.DOUBLE else raw
        pauseThenRearm()
        val answering = answerPress != null
        val action = GestureRules.action(tap, answering, settings.chosen)
        // Склеенная пара — одно касание: третье вслед за ней снова пару не образует.
        lastTap = if (paired) null else tap
        lastAcceptedAt = now
        lastIgnored = action == GestureAction.IGNORE
        Log.d(TAG, "gesture: $what → tap=$tap${if (paired) " (pair of single taps)" else ""} action=$action (answering=$answering, state=${_state.value})")
        when (action) {
            GestureAction.INTERRUPT -> answerPress?.invoke()
            GestureAction.ASK -> onAsk?.invoke() ?: onNoScreen?.invoke()
            GestureAction.IGNORE -> Unit
        }
    }

    /** Очки сочли, что звук на паузе: останавливаем тишину по-настоящему и чуть позже включаем снова. */
    private fun pauseThenRearm() {
        stopSilence()
        setState(PlaybackState.STATE_PAUSED)
        main.removeCallbacks(rearm)
        main.postDelayed(rearm, REARM_MS)
    }

    private fun play() {
        startSilence()
        setState(PlaybackState.STATE_PLAYING)
    }

    private fun setState(state: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, 0, 1f)
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT,
                )
                .build(),
        )
    }

    private fun startSilence() {
        if (silence != null) return
        val frames = SILENCE_RATE / 2
        silence = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(Speaker.ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENCE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
                .apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                    play()
                }
        }.onFailure { Log.w(TAG, "silent track failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun stopSilence() {
        silence?.let { runCatching { it.stop() }; it.release() }
        silence = null
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private companion object {
        const val TAG = "Audio"
        const val SILENCE_RATE = 8000

        /** Пауза между остановкой тишины и новым запуском: с 3 с на очках доходили все касания подряд. */
        const val REARM_MS = 2_000L
        const val CALL_RECHECK_MS = 2_000L
        val MEDIA_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        val CALL_USAGES = setOf(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
    }
}
