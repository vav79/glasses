package ru.vasev.glasses.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.claude.ClaudeClient
import ru.vasev.glasses.claude.Conversation
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

/** Обмен в чате. [remembered] = false — Claude его уже не помнит (показывается бледным). */
data class ChatItem(
    val id: Long,
    val question: String,
    val answer: String,
    val frame: Bitmap?,
    val remembered: Boolean,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val chat: List<ChatItem> = emptyList(),
    /** Вопрос и кадр текущего, ещё не завершённого обмена. */
    val pendingQuestion: String = "",
    val pendingFrame: Bitmap? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val glasses = (app as GlassesApp).glasses
    private val speech = SpeechInput(app)
    private val speaker = Speaker(app)
    private val claude = ClaudeClient()

    // История живёт только в памяти: перезапуск приложения начинает разговор заново.
    private val conversation = Conversation()
    private val frames = mutableMapOf<Long, Bitmap>()
    private var lastActivity = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val glassesStatus: StateFlow<GlassesStatus> = glasses.status
    val hasApiKey: Boolean = claude.hasApiKey

    /** Запрос DAT-разрешения на камеру; подставляется из MainActivity. */
    var cameraPermissionRequester: (suspend () -> Boolean)? = null

    private var job: Job? = null
    private var idleJob: Job? = null

    fun setMockMode(enabled: Boolean) = glasses.setMockMode(enabled)

    /** «Спросить»: голос → Claude → голос. */
    fun ask() = run(withFrame = false)

    /** «Что я вижу»: кадр с очков + голос → Claude → голос. */
    fun whatDoISee() = run(withFrame = true)

    /** Только для debug-сборок: прогон сценария с готовым текстом вместо распознавания речи. */
    fun debugAsk(question: String, withFrame: Boolean) = run(withFrame, presetQuestion = question)

    fun stop() {
        job?.cancel()
        speaker.stop()
        _state.update { it.copy(phase = Phase.IDLE, pendingQuestion = "", pendingFrame = null) }
    }

    /** Кнопка «Новый разговор». */
    fun newConversation() {
        stop()
        resetConversation()
    }

    private fun run(withFrame: Boolean, presetQuestion: String? = null) {
        job?.cancel()
        speaker.stop()
        job = viewModelScope.launch {
            if (lastActivity != 0L && SystemClock.elapsedRealtime() - lastActivity >= IDLE_RESET_MS) {
                resetConversation()
            }
            _state.update { it.copy(pendingQuestion = "", pendingFrame = null, error = null) }
            try {
                var jpeg: ByteArray? = null
                if (withFrame) {
                    setPhase(Phase.CAPTURING)
                    val requester = cameraPermissionRequester ?: { false }
                    jpeg = glasses.captureJpeg(requester)
                    val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    _state.update { it.copy(pendingFrame = preview) }
                }

                setPhase(Phase.LISTENING)
                val question = presetQuestion
                    ?: speech.listen { partial -> _state.update { it.copy(pendingQuestion = partial) } }
                _state.update { it.copy(pendingQuestion = question) }

                if (Conversation.isResetCommand(question)) {
                    resetConversation()
                    setPhase(Phase.SPEAKING)
                    speaker.speak("Начинаем новый разговор.")
                    setPhase(Phase.IDLE)
                    return@launch
                }

                setPhase(Phase.THINKING)
                val answer = claude.ask(conversation.turnsFor(question, jpeg))
                val exchange = conversation.add(question, jpeg, answer)
                _state.value.pendingFrame?.let { frames[exchange.id] = it }
                _state.update { it.copy(pendingQuestion = "", pendingFrame = null) }
                publishChat()
                touch()

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

    private fun resetConversation() {
        idleJob?.cancel()
        conversation.clear()
        frames.clear()
        lastActivity = 0L
        _state.update { UiState(phase = it.phase) }
    }

    /** Перезапускает таймер автосброса после 5 минут тишины. */
    private fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(IDLE_RESET_MS)
            if (_state.value.phase == Phase.IDLE) resetConversation()
        }
    }

    private fun publishChat() {
        val start = conversation.memoryStart
        val chat = conversation.exchanges.mapIndexed { index, exchange ->
            ChatItem(exchange.id, exchange.question, exchange.answer, frames[exchange.id], remembered = index >= start)
        }
        _state.update { it.copy(chat = chat) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    override fun onCleared() {
        speaker.shutdown()
    }

    private companion object {
        const val IDLE_RESET_MS = 5 * 60 * 1000L
    }
}
