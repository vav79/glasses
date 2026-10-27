package ru.vasev.glasses.debug

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.ui.theme.GlassesTheme

/**
 * Проба DAT Speech на настоящих очках (только debug): какой язык отдают очки, понимают ли русский, задержки.
 * adb shell am start -n ru.vasev.glasses/.debug.SpeechProbeActivity [--ez autostart true]
 * Всё пишется в лог SpeechProbe и на экран. Разрешение микрофона очков — кнопкой (Meta AI, только по действию пользователя).
 */
class SpeechProbeActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private var session: DeviceSession? = null
    private var speech: Speech? = null
    private val jobs = mutableListOf<Job>()
    private var startedAt = 0L

    private val micPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        log("mic permission: ${result.getOrNull() ?: result.errorOrNull()?.description}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autostart = intent.getBooleanExtra("autostart", false)
        setContent {
            GlassesTheme(dark = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("DAT Speech probe", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { micPermission.launch(Permission.MICROPHONE) }) { Text("Mic permission") }
                            Button(onClick = ::start) { Text("Start") }
                            Button(onClick = ::stop) { Text("Stop") }
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
        lifecycleScope.launch {
            val status = Wearables.checkPermissionStatus(Permission.MICROPHONE)
            log("registration=${Wearables.registrationState.value} devices=${Wearables.devices.value.size} " +
                "mic=${status.getOrNull() ?: status.errorOrNull()?.description}")
            if (autostart) start()
        }
    }

    private fun start() {
        if (session != null) return log("already running")
        lifecycleScope.launch {
            startedAt = SystemClock.elapsedRealtime()
            val created = Wearables.createSession(AutoDeviceSelector())
            val s = created.getOrNull() ?: return@launch log("createSession failed: ${created.errorOrNull()}")
            session = s
            jobs += launch { s.errors.collect { log("session error=$it") } }
            s.start()
            val state = withTimeoutOrNull(15_000) { s.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } }
            log("session state=$state")
            if (state != DeviceSessionState.STARTED) return@launch stop()

            val added = s.addSpeech()
            val sp = added.getOrNull() ?: return@launch log("addSpeech failed: ${added.errorOrNull()}").also { stop() }
            speech = sp
            var utteranceStart = 0L
            jobs += launch { sp.state.collect { log("speech state=$it") } }
            jobs += launch { sp.locale.collect { if (it != null) log("locale=$it") } }
            jobs += launch { sp.errors.collect { if (it != null) log("speech error=$it details=${sp.errorDetails.value}") } }
            jobs += launch {
                sp.transcriptions.collect { r ->
                    r ?: return@collect
                    val now = SystemClock.elapsedRealtime()
                    if (utteranceStart == 0L) utteranceStart = now
                    val tag = if (r.isFinal) "FINAL" else "partial"
                    log("$tag conf=${"%.2f".format(r.confidence)} +${now - utteranceStart}ms: ${r.text}")
                    if (r.isFinal) utteranceStart = 0L
                }
            }
            val started = sp.start()
            log("speech.start: ${started.errorOrNull() ?: "ok"}")
        }
    }

    private fun stop() {
        speech?.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.let { s ->
            if (speech != null) s.removeSpeech()
            s.stop()
        }
        speech = null
        session = null
        log("stopped")
    }

    private fun log(text: String) {
        val line = "${(SystemClock.elapsedRealtime() - startedAt).takeIf { startedAt > 0 } ?: 0}ms $text"
        Log.d(TAG, line)
        runOnUiThread { lines += line }
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SpeechProbe"
    }
}
