package ru.vasev.glasses.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.claude.ClaudeClient
import ru.vasev.glasses.glasses.GlassesStatus
import ru.vasev.glasses.speech.Speaker
import ru.vasev.glasses.speech.SpeechInput

enum class Phase(val label: String) {
    IDLE(""),
    CAPTURING("Снимаю кадр с очков…"),
    LISTENING("Слушаю вопрос…"),
    THINKING("Думаю…"),
    SPEAKING("Отвечаю…"),
}

data class UiState(
    val phase: Phase = Phase.IDLE,
    val question: String = "",
    val answer: String = "",
    val error: String? = null,
    val frame: Bitmap? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val glasses = (app as GlassesApp).glasses
    private val speech = SpeechInput(app)
    private val speaker = Speaker(app)
    private val claude = ClaudeClient()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val glassesStatus: StateFlow<GlassesStatus> = glasses.status
    val hasApiKey: Boolean = claude.hasApiKey

    /** Запрос DAT-разрешения на камеру; подставляется из MainActivity. */
    var cameraPermissionRequester: (suspend () -> Boolean)? = null

    private var job: Job? = null

    fun setMockMode(enabled: Boolean) = glasses.setMockMode(enabled)

    /** «Спросить»: голос → Claude → голос. */
    fun ask() = run(withFrame = false)

    /** «Что я вижу»: кадр с очков + голос → Claude → голос. */
    fun whatDoISee() = run(withFrame = true)

    fun stop() {
        job?.cancel()
        speaker.stop()
        _state.update { it.copy(phase = Phase.IDLE) }
    }

    /** Только для debug-сборок: прогон сценария с готовым текстом вместо распознавания речи. */
    fun debugAsk(question: String, withFrame: Boolean) = run(withFrame, presetQuestion = question)

    private fun run(withFrame: Boolean, presetQuestion: String? = null) {
        job?.cancel()
        speaker.stop()
        job = viewModelScope.launch {
            _state.value = UiState()
            try {
                var jpeg: ByteArray? = null
                if (withFrame) {
                    setPhase(Phase.CAPTURING)
                    val requester = cameraPermissionRequester ?: { false }
                    jpeg = glasses.captureJpeg(requester)
                    val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    _state.update { it.copy(frame = preview) }
                }

                setPhase(Phase.LISTENING)
                val question = presetQuestion
                    ?: speech.listen { partial -> _state.update { it.copy(question = partial) } }
                _state.update { it.copy(question = question) }

                setPhase(Phase.THINKING)
                val answer = claude.ask(question, jpeg)
                _state.update { it.copy(answer = answer) }

                setPhase(Phase.SPEAKING)
                speaker.speak(answer)
                setPhase(Phase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Request failed", e)
                val message = e.message ?: "Что-то пошло не так."
                _state.update { it.copy(phase = Phase.IDLE, error = message) }
            }
        }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    override fun onCleared() {
        speaker.shutdown()
    }
}
