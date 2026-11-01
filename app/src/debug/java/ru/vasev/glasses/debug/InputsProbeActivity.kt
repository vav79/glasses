package ru.vasev.glasses.debug

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
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
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.speech.Speaker
import ru.vasev.glasses.ui.theme.GlassesTheme

/**
 * Проба жестов очков (только debug): что приходит через DAT Inputs и что — медиакнопкой Bluetooth.
 * adb shell am start -n ru.vasev.glasses/.debug.InputsProbeActivity
 * Лог SpeechProbe-подобный: тег InputsProbe, на экран — то же.
 *  - «DAT Inputs» — сессия + addInputs со всеми источниками, каждое событие с источником и временем.
 *  - «Media: session» — медиасессия «играет», но звука нет: доходят ли кнопки, когда мы ничего не играем.
 *  - «Media: + silence» — как HeadsetButtons во время ответа (сессия + беззвучная дорожка).
 */
class InputsProbeActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private var session: DeviceSession? = null
    private val jobs = mutableListOf<Job>()
    private var media: MediaSession? = null
    private var silence: AudioTrack? = null
    private val startedAt = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassesTheme(dark = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Glasses gestures probe", style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::startInputs) { Text("DAT Inputs") }
                            Button(onClick = ::stopInputs) { Text("Inputs off") }
                            Button(onClick = { startMedia(withSilence = false) }) { Text("Media: session") }
                            Button(onClick = { startMedia(withSilence = true) }) { Text("Media: + silence") }
                            Button(onClick = ::stopMedia) { Text("Media off") }
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
        val audio = getSystemService(AudioManager::class.java)
        log("registration=${Wearables.registrationState.value} devMode=${Wearables.isDevMode} other_music_active=${audio.isMusicActive}")
        when (intent.getStringExtra("start")) {
            "inputs" -> startInputs()
            "media" -> startMedia(withSilence = false)
            "silence" -> startMedia(withSilence = true)
        }
    }

    private fun startInputs() {
        if (session != null) return log("inputs: already running")
        lifecycleScope.launch {
            val created = Wearables.createSession(AutoDeviceSelector())
            val s = created.getOrNull() ?: return@launch log("createSession failed: ${created.errorOrNull()}")
            session = s
            jobs += launch { s.errors.collect { log("session error=$it") } }
            // Одно касание очки ставят сессию на паузу, удержание — останавливают: видно здесь.
            jobs += launch { s.state.collect { log("session state=$it") } }
            s.start()
            val state = withTimeoutOrNull(15_000) { s.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
            log("session state=$state")
            if (state != DeviceSessionState.STARTED) return@launch stopInputs()
            // По умолчанию — все известные источники, кроме UNKNOWN; Back не отдаём системе.
            val added = s.addInputs(InputsConfiguration())
            val inputs = added.getOrNull() ?: return@launch log("addInputs failed: ${added.errorOrNull()}").also { stopInputs() }
            log("inputs sources=${inputs.configuration.sources} consumeBack=${inputs.configuration.consumeBack}")
            jobs += launch { inputs.state.collect { log("inputs state=$it") } }
            jobs += launch { inputs.errors.collect { if (it != null) log("inputs error=$it") } }
            jobs += launch { inputs.events.collect { log("EVENT ${describe(it)}") } }
        }
    }

    private fun describe(e: InputEvent): String = when (e) {
        is InputEvent.Nav -> "Nav ${e.direction}"
        is InputEvent.Select -> "Select"
        is InputEvent.Back -> "Back"
        is InputEvent.Button -> "Button ${e.button}"
        is InputEvent.Capture -> "Capture ${e.pressType}"
        is InputEvent.Drag -> "Drag ${e.action} x=${e.x} y=${e.y}"
    } + " source=${e.source} t=${e.timestampMs}"

    private fun stopInputs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.let { s ->
            s.removeInputs()
            s.stop()
        }
        session = null
        log("inputs: stopped")
    }

    private fun startMedia(withSilence: Boolean) {
        stopMedia()
        if (withSilence) startSilence()
        media = MediaSession(this, "InputsProbe").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = keyEvent(mediaButtonIntent)
                    log("MEDIA key ${event?.let { "${KeyEvent.keyCodeToString(it.keyCode)} action=${it.action} repeat=${it.repeatCount}" }}")
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
                // Очки шлют PAUSE/PLAY по тому, что, по их мнению, сейчас играет: переключаем состояние,
                // иначе второе касание не приходит.
                override fun onPlay() = log("MEDIA onPlay").also { setState(PlaybackState.STATE_PLAYING) }
                // Пауза — как в будущем «готов → вопрос»: тишина останавливается, через 3 с снова «играем» с тишиной.
                override fun onPause() = log("MEDIA onPause").also {
                    setState(PlaybackState.STATE_PAUSED)
                    val rearm = silence != null
                    silence?.let { runCatching { it.stop() }; it.release() }
                    silence = null
                    if (rearm) {
                        window.decorView.postDelayed({
                            if (media == null) return@postDelayed
                            startSilence()
                            setState(PlaybackState.STATE_PLAYING)
                            log("re-armed: silence + PLAYING")
                        }, 3000)
                    }
                }
                override fun onStop() = log("MEDIA onStop")
                override fun onSkipToNext() = log("MEDIA onSkipToNext")
                override fun onSkipToPrevious() = log("MEDIA onSkipToPrevious")
            })
            isActive = true
        }
        setState(PlaybackState.STATE_PLAYING)
        log("media session active, silence=$withSilence, other_music_active=${getSystemService(AudioManager::class.java).isMusicActive}")
    }

    private fun setState(state: Int) {
        media?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, 0, 1f)
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .build(),
        )
    }

    private fun startSilence() {
        val frames = 4000
        silence = AudioTrack.Builder()
            .setAudioAttributes(Speaker.ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(8000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
            .apply {
                write(ShortArray(frames), 0, frames)
                setLoopPoints(0, frames, -1)
                play()
            }
    }

    private fun stopMedia() {
        media?.let {
            it.isActive = false
            it.release()
            log("media session released")
        }
        media = null
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

    private fun log(text: String) {
        val line = "${SystemClock.elapsedRealtime() - startedAt}ms $text"
        Log.d(TAG, line)
        runOnUiThread { lines += line }
    }

    override fun onDestroy() {
        stopMedia()
        stopInputs()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "InputsProbe"
    }
}
