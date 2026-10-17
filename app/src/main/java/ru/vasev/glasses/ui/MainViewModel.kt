package ru.vasev.glasses.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.PhoneCameraException
import ru.vasev.glasses.claude.AssistantClient
import ru.vasev.glasses.claude.Conversation
import ru.vasev.glasses.glasses.GlassesException
import ru.vasev.glasses.glasses.GlassesStatus
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.speech.AudioChannel

enum class Phase(@StringRes val labelRes: Int?) {
    IDLE(null),
    CAPTURING(R.string.phase_capturing),
    LISTENING(R.string.phase_listening),
    THINKING(R.string.phase_thinking),
    SPEAKING(R.string.phase_speaking),
}

/** Обмен в чате. [remembered] = false — модель его уже не помнит (показывается бледным). [model] — метка модели. */
data class ChatItem(
    val id: Long,
    val question: String,
    val answer: String,
    val frame: Bitmap?,
    val remembered: Boolean,
    val model: ModelTag?,
    /** Откуда снимок (очки, телефон, галерея, симулятор); подпись — на языке экрана. */
    val frameSource: ImageSource? = null,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val chat: List<ChatItem> = emptyList(),
    /** Вопрос и кадр текущего, ещё не завершённого обмена. */
    val pendingQuestion: String = "",
    val pendingFrame: Bitmap? = null,
    val pendingFrameSource: ImageSource? = null,
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
    private val labels = mutableMapOf<Long, ModelTag>()
    private val frameSources = mutableMapOf<Long, ImageSource>()
    private var lastActivity = 0L

    private val imageSources = (app as GlassesApp).imageSources

    /** Камера телефона — видоискатель привязывается к ней из MainScreen. */
    val phoneCamera = (app as GlassesApp).phoneCamera
    val imageSource: StateFlow<ImageSource> = imageSources.source
    val availableSources: List<ImageSource> = imageSources.available

    /** Системный выбор фото (без доступа ко всей галерее); подставляется из MainActivity. null — ничего не выбрано. */
    var galleryPicker: (suspend () -> Uri?)? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Громкость голоса 0..1 во время прослушивания — отдельно от [state], чтобы не перерисовывать весь экран. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val haptics = Haptics(app)

    val glassesStatus: StateFlow<GlassesStatus> = glasses.status

    /** Модель «Ассистента» и есть ли для неё ключ. */
    val model: StateFlow<ModelSpec> = llm.model(Role.ASSISTANT)
    val hasApiKey: StateFlow<Boolean> = llm.ready(Role.ASSISTANT)
        .stateIn(viewModelScope, SharingStarted.Eagerly, (app as GlassesApp).apiKeys.has(model.value.provider))

    /** Запрос DAT-разрешения на камеру; подставляется из MainActivity. */
    var cameraPermissionRequester: (suspend () -> Boolean)? = null

    private var job: Job? = null
    private var idleJob: Job? = null

    /** Разрешение на камеру телефона запрашивает MainActivity до вызова. */
    fun setImageSource(source: ImageSource) = imageSources.set(source)

    /** «Спросить»: голос → модель → голос. */
    fun ask() = run(withFrame = false)

    /** «Что я вижу»: снимок из выбранного источника + голос → модель → голос. */
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
            _state.update { it.copy(pendingQuestion = "", pendingFrame = null, pendingFrameSource = null, error = null) }
            // Язык общения берётся на каждый вопрос: смена языка в настройках действует со следующего вопроса.
            val language = LanguageSettings.current.value
            try {
                var jpeg: ByteArray? = null
                if (withFrame) {
                    val source = imageSources.source.value
                    setPhase(Phase.CAPTURING)
                    jpeg = when (source) {
                        ImageSource.GLASSES, ImageSource.SIMULATOR -> {
                            if (!glasses.status.value.ready) {
                                // Автоматически на телефон не переключаем: он может лежать в кармане.
                                failAloud(
                                    L.s(if (source == ImageSource.GLASSES) R.string.glasses_not_connected else R.string.simulator_not_ready),
                                )
                                return@launch
                            }
                            glasses.captureJpeg(cameraPermissionRequester ?: { false })
                        }
                        ImageSource.PHONE_CAMERA -> phoneCamera.capture()
                        ImageSource.GALLERY -> {
                            val uri = galleryPicker?.invoke()
                            if (uri == null) {
                                setPhase(Phase.IDLE)
                                return@launch
                            }
                            val resolver = getApplication<Application>().contentResolver
                            withContext(Dispatchers.Default) { ImagePrep.fromUri(resolver, uri) }
                        }
                    }
                    val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    _state.update { it.copy(pendingFrame = preview, pendingFrameSource = source) }
                }

                setPhase(Phase.LISTENING)
                val question = presetQuestion ?: run {
                    haptics.listenStart()
                    try {
                        speech.listen(
                            language.speechTag,
                            onPartial = { partial -> _state.update { it.copy(pendingQuestion = partial) } },
                            onLevel = { _micLevel.value = it },
                        ).text
                    } finally {
                        _micLevel.value = 0f
                        haptics.listenEnd()
                    }
                }
                _state.update { it.copy(pendingQuestion = question) }

                if (Conversation.isResetCommand(question, L.array(R.array.reset_commands))) {
                    resetConversation()
                    setPhase(Phase.SPEAKING)
                    audio.speak(L.s(R.string.say_new_conversation), language.locale, AudioChannel.ME)
                    setPhase(Phase.IDLE)
                    return@launch
                }

                setPhase(Phase.THINKING)
                val result = assistant.ask(conversation.turnsFor(question, jpeg), language)
                val answer = result.text ?: L.s(R.string.say_cannot_answer)
                val exchange = conversation.add(question, jpeg, answer)
                _state.value.pendingFrame?.let { frames[exchange.id] = it }
                _state.value.pendingFrameSource?.let { frameSources[exchange.id] = it }
                labels[exchange.id] = result.tag
                _state.update { it.copy(pendingQuestion = "", pendingFrame = null, pendingFrameSource = null) }
                publishChat()
                touch()

                setPhase(Phase.SPEAKING)
                val spoken = if (result.viaFallback) L.s(R.string.say_fallback_answers, answer) else answer
                audio.speak(spoken, language.locale, AudioChannel.ME)
                setPhase(Phase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                Log.w(TAG, "LLM: ${e.message}")
                failAloud(e.message.orEmpty())
            } catch (e: GlassesException) {
                Log.w(TAG, "Glasses: ${e.message}")
                failAloud(e.message.orEmpty())
            } catch (e: PhoneCameraException) {
                Log.w(TAG, "Phone camera: ${e.message}")
                failAloud(e.message.orEmpty())
            } catch (e: Exception) {
                Log.w(TAG, "Request failed", e)
                val message = e.message ?: L.s(R.string.something_went_wrong)
                _state.update { it.copy(phase = Phase.IDLE, error = message) }
            }
        }
    }

    /** Ошибка на экран и голосом: с очками на экран не смотрят («нет интернета», «очки не подключены» и т. п.). */
    private suspend fun failAloud(message: String) {
        _state.update { it.copy(phase = Phase.SPEAKING, error = message) }
        runCatching { audio.speak(message, LanguageSettings.current.value.locale, AudioChannel.ME) }
        setPhase(Phase.IDLE)
    }

    private fun resetConversation() {
        idleJob?.cancel()
        conversation.clear()
        frames.clear()
        labels.clear()
        frameSources.clear()
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
                model = labels[exchange.id],
                frameSource = frameSources[exchange.id],
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
    }
}
