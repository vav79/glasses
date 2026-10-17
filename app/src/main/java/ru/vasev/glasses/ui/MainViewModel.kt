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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.claude.AssistantClient
import ru.vasev.glasses.claude.Conversation
import ru.vasev.glasses.glasses.GlassesStatus
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.Speaker

enum class Phase(val label: String) {
    IDLE(""),
    CAPTURING("Снимаю кадр с очков…"),
    LISTENING("Слушаю вопрос…"),
    THINKING("Думаю…"),
    SPEAKING("Отвечаю…"),
}

/** Обмен в чате. [remembered] = false — модель его уже не помнит (показывается бледным). [model] — метка модели. */
data class ChatItem(
    val id: Long,
    val question: String,
    val answer: String,
    val frame: Bitmap?,
    val remembered: Boolean,
    val model: String,
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
    private val speech = (app as GlassesApp).speechSource
    private val audio = (app as GlassesApp).audioOutput
    private val llm = (app as GlassesApp).llm
    private val assistant = AssistantClient(llm)

    // История живёт только в памяти: перезапуск приложения начинает разговор заново.
    private val conversation = Conversation()
    private val frames = mutableMapOf<Long, Bitmap>()
    private val labels = mutableMapOf<Long, String>()
    private var lastActivity = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val glassesStatus: StateFlow<GlassesStatus> = glasses.status

    /** Модель «Ассистента» и есть ли для неё ключ. */
    val model: StateFlow<ModelSpec> = llm.model(Role.ASSISTANT)
    val hasApiKey: StateFlow<Boolean> = llm.ready(Role.ASSISTANT)
        .stateIn(viewModelScope, SharingStarted.Eagerly, (app as GlassesApp).apiKeys.has(model.value.provider))

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
        audio.stop()
        _state.update { it.copy(phase = Phase.IDLE, pendingQuestion = "", pendingFrame = null) }
    }

    /** Кнопка «Новый разговор». */
    fun newConversation() {
        stop()
        resetConversation()
    }

    private fun run(withFrame: Boolean, presetQuestion: String? = null) {
        job?.cancel()
        audio.stop()
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
                    ?: speech.listen(RU_TAG) { partial -> _state.update { it.copy(pendingQuestion = partial) } }.text
                _state.update { it.copy(pendingQuestion = question) }

                if (Conversation.isResetCommand(question)) {
                    resetConversation()
                    setPhase(Phase.SPEAKING)
                    audio.speak("Начинаем новый разговор.", Speaker.RUSSIAN, AudioChannel.ME)
                    setPhase(Phase.IDLE)
                    return@launch
                }

                setPhase(Phase.THINKING)
                val result = assistant.ask(conversation.turnsFor(question, jpeg))
                val answer = result.text ?: "Извините, на это я ответить не могу."
                val exchange = conversation.add(question, jpeg, answer)
                _state.value.pendingFrame?.let { frames[exchange.id] = it }
                labels[exchange.id] = result.label
                _state.update { it.copy(pendingQuestion = "", pendingFrame = null) }
                publishChat()
                touch()

                setPhase(Phase.SPEAKING)
                val spoken = if (result.viaFallback) "Отвечает запасная модель. $answer" else answer
                audio.speak(spoken, Speaker.RUSSIAN, AudioChannel.ME)
                setPhase(Phase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                // С очками на экран не смотрят: «нет интернета», «фото не отправлено» и т. п. — ещё и голосом.
                Log.w(TAG, "LLM: ${e.message}")
                _state.update { it.copy(phase = Phase.SPEAKING, error = e.message) }
                runCatching { audio.speak(e.message.orEmpty(), Speaker.RUSSIAN, AudioChannel.ME) }
                setPhase(Phase.IDLE)
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
        labels.clear()
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
            ChatItem(
                exchange.id,
                exchange.question,
                exchange.answer,
                frames[exchange.id],
                remembered = index >= start,
                model = labels[exchange.id].orEmpty(),
            )
        }
        _state.update { it.copy(chat = chat) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    override fun onCleared() {
        audio.stop()
    }

    private companion object {
        const val IDLE_RESET_MS = 5 * 60 * 1000L
        const val RU_TAG = "ru-RU"
    }
}
