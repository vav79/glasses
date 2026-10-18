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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.R
import ru.vasev.glasses.answer.SentenceSplitter
import ru.vasev.glasses.answer.SpokenAnswer
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.PhoneCameraException
import ru.vasev.glasses.claude.AssistantClient
import ru.vasev.glasses.claude.Conversation
import ru.vasev.glasses.glasses.GlassesException
import ru.vasev.glasses.glasses.GlassesStatus
import ru.vasev.glasses.i18n.AppLanguage
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.llm.StreamedAnswer
import ru.vasev.glasses.llm.TurnMetrics
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.SpeechException
import ru.vasev.glasses.speech.Talker

enum class Phase(@StringRes val labelRes: Int?) {
    IDLE(null),
    CAPTURING(R.string.phase_capturing),
    LISTENING(R.string.phase_listening),
    THINKING(R.string.phase_thinking),
    SPEAKING(R.string.phase_speaking),
    ;

    /** Идёт ответ: кнопка микрофона перебивает его и сразу слушает новый вопрос. */
    val interruptible: Boolean get() = this == THINKING || this == SPEAKING
}

/** Обмен в чате. [remembered] = false — модель его уже не помнит (показывается бледным). [model] — метка модели. */
data class ChatItem(
    val id: Long,
    val question: String,
    /** Суть ответа — она же звучала. */
    val answer: String,
    val frame: Bitmap?,
    val remembered: Boolean,
    val model: ModelTag?,
    /** Откуда снимок (очки, телефон, галерея, симулятор); подпись — на языке экрана. */
    val frameSource: ImageSource? = null,
    /** Подробности — только на экране, по «Подробнее». */
    val details: String = "",
    val interrupted: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val chat: List<ChatItem> = emptyList(),
    /** Вопрос и кадр текущего, ещё не завершённого обмена. */
    val pendingQuestion: String = "",
    val pendingFrame: Bitmap? = null,
    val pendingFrameSource: ImageSource? = null,
    /** Ответ, который ещё пишется: суть и подробности по мере потока. */
    val pendingAnswer: String = "",
    val pendingDetails: String = "",
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val glasses = (app as GlassesApp).glasses
    private val speech = (app as GlassesApp).speechSource
    private val audio = (app as GlassesApp).audioOutput
    private val router = (app as GlassesApp).audioRouter
    private val headsetButtons = (app as GlassesApp).headsetButtons
    private val profile = (app as GlassesApp).profile
    private val llm = (app as GlassesApp).llm
    private val assistant = AssistantClient(llm)
    private val abbreviations = SentenceSplitter.loadAbbreviations(app)

    // История живёт только в памяти: перезапуск приложения начинает разговор заново.
    private val conversation = Conversation()
    private val frames = mutableMapOf<Long, Bitmap>()
    private val labels = mutableMapOf<Long, ModelTag>()
    private val frameSources = mutableMapOf<Long, ImageSource>()
    private val parts = mutableMapOf<Long, Pair<String, String>>()
    private val interrupted = mutableSetOf<Long>()
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

    /**
     * «Спросить»: голос → модель → голос. Во время ответа это же — «перебить»: запрос и речь
     * останавливаются, и сразу начинается прослушивание нового вопроса.
     */
    fun ask() = run(withFrame = false)

    /** «Что я вижу»: снимок из выбранного источника + голос → модель → голос. */
    fun whatDoISee() = run(withFrame = true)

    /** Только для debug-сборок: прогон сценария с готовым текстом вместо распознавания речи. */
    fun debugAsk(question: String, withFrame: Boolean) = run(withFrame, presetQuestion = question)

    fun stop() {
        job?.cancel()
        audio.stop()
        _state.update { it.copy(phase = Phase.IDLE, pendingQuestion = "", pendingFrame = null, pendingAnswer = "", pendingDetails = "") }
    }

    /** Кнопка «Новый разговор». */
    fun newConversation() {
        stop()
        resetConversation()
    }

    private fun run(withFrame: Boolean, presetQuestion: String? = null) {
        if (_state.value.phase.interruptible) Log.d(TAG, "Assistant: answer interrupted, listening for a new question")
        job?.cancel()
        audio.stop()
        job = viewModelScope.launch {
            if (lastActivity != 0L && SystemClock.elapsedRealtime() - lastActivity >= IDLE_RESET_MS) {
                resetConversation()
            }
            _state.update {
                it.copy(pendingQuestion = "", pendingFrame = null, pendingFrameSource = null, pendingAnswer = "", pendingDetails = "", error = null)
            }
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
                // Конец моей фразы — для замера «конец фразы → первый звук»; при debug-запуске — момент прихода вопроса.
                var endOfSpeechAt = SystemClock.elapsedRealtime()
                val question = presetQuestion ?: run {
                    haptics.listenStart()
                    try {
                        speech.listen(
                            language.speechTag,
                            onPartial = { partial -> _state.update { it.copy(pendingQuestion = partial) } },
                            onLevel = { _micLevel.value = it },
                        ).also { endOfSpeechAt = it.endOfSpeechAt }.text
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

                answer(question, jpeg, language, TurnMetrics(endOfSpeechAt))
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

    /**
     * Ответ потоком: каждое законченное предложение сути сразу встаёт в очередь озвучки, подробности идут
     * только в чат. Пока ответ идёт, кнопка гарнитуры (и, если включено, голосовое «стоп») его прерывают,
     * а музыка других приложений приглушена.
     */
    private suspend fun answer(question: String, jpeg: ByteArray?, language: AppLanguage, metrics: TurnMetrics) {
        setPhase(Phase.THINKING)
        val spoken = SpokenAnswer(SentenceSplitter(abbreviations))
        val sentences = Channel<String>(Channel.UNLIMITED)
        var streamed: StreamedAnswer? = null
        var committedId: Long? = null
        var finished = false
        var wasCancelled = false

        audio.holdFocus()
        headsetButtons.listen {
            Log.d(TAG, "Assistant: interrupted by headset button")
            ask()
        }
        try {
            coroutineScope {
                // Голосовое «стоп»: микрофон гарнитуры включается параллельно с запросом к модели,
                // чтобы первая фраза уже звучала по тому же маршруту (без переключения посреди ответа).
                val micHeld = if (profile.voiceStop.value && router.headset.value?.mic != null) async { router.holdMic() } else null
                val voiceStop = launch {
                    if (micHeld?.await() == true) listenForStop(language)
                }
                val speaker = launch {
                    micHeld?.await()
                    for (sentence in sentences) {
                        audio.speak(sentence, language.locale, AudioChannel.ME) {
                            if (metrics.firstSoundAt == null) {
                                metrics.firstSoundAt = SystemClock.elapsedRealtime()
                                Log.d(TAG, "Assistant: end_of_speech→sound=${metrics.firstSoundMs}ms")
                                setPhase(Phase.SPEAKING)
                            }
                        }
                    }
                }

                val result = assistant.stream(
                    conversation.turnsFor(question, jpeg),
                    language,
                    metrics,
                    onFallback = { sentences.trySend(L.s(R.string.say_fallback_intro)) },
                    onText = { delta ->
                        spoken.feed(delta).forEach { sentences.trySend(it) }
                        _state.update { it.copy(pendingAnswer = spoken.summaryText, pendingDetails = spoken.detailsText) }
                    },
                )
                streamed = result
                spoken.finish().forEach { sentences.trySend(it) }
                if (result.answer.text == null) sentences.trySend(L.s(R.string.say_cannot_answer))
                sentences.close()

                // Ответ готов — в чат и в память сразу, не дожидаясь конца озвучки («Подробнее» доступно уже сейчас).
                committedId = commit(question, jpeg, spoken, result.answer.text == null, result.answer.tag, wasInterrupted = false)
                speaker.join()
                voiceStop.cancel()
            }
            finished = true
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } finally {
            sentences.close()
            headsetButtons.stop()
            router.unholdMic()
            audio.releaseFocus()
            if (!finished) {
                // Перебили или ответ оборвался: в чат и в память — то, что успело прийти.
                val id = committedId ?: if (spoken.fullText.isNotEmpty()) {
                    // Ответ не дописан — метка по выбранной модели (запасная к этому моменту ещё не включалась).
                    val tag = streamed?.answer?.tag ?: ModelTag(model.value.title, viaFallback = false, autoVision = false)
                    commit(question, jpeg, spoken, refused = false, tag = tag, wasInterrupted = wasCancelled)
                } else {
                    null
                }
                if (wasCancelled && id != null) {
                    interrupted += id
                    publishChat()
                }
                if (id == null) _state.update { it.copy(pendingAnswer = "", pendingDetails = "") }
            }
            streamed?.let { llm.finishTurn(it, metrics, interrupted = wasCancelled) }
        }
    }

    /** Слушает микрофон гарнитуры, пока звучит ответ; «стоп» / «stop» — остановить ответ (без нового вопроса). */
    private suspend fun CoroutineScope.listenForStop(language: AppLanguage) {
        val commands = L.array(R.array.stop_commands)
        val heard = CompletableDeferred<Unit>()
        while (isActive && !heard.isCompleted) {
            try {
                val phrase = speech.listen(language.speechTag, Talker.USER, onPartial = { partial ->
                    if (Conversation.isCommand(partial, commands)) heard.complete(Unit)
                }).text
                if (Conversation.isCommand(phrase, commands)) heard.complete(Unit)
            } catch (e: SpeechException) {
                // Тишина и временные сбои — слушаем дальше, остальное (нет разрешения и т. п.) — выходим.
                if (!e.noSpeech && !e.transient) return
            }
        }
        if (heard.isCompleted) {
            Log.d(TAG, "Assistant: stopped by voice")
            stop()
        }
    }

    /** Записывает обмен в память разговора и чат; возвращает его id. */
    private fun commit(
        question: String,
        jpeg: ByteArray?,
        spoken: SpokenAnswer,
        refused: Boolean,
        tag: ModelTag?,
        wasInterrupted: Boolean,
    ): Long {
        val summary = if (refused) L.s(R.string.say_cannot_answer) else spoken.summaryText
        val details = if (refused) "" else spoken.detailsText
        // В память — ответ целиком, без маркера: так модель помнит и подробности.
        val exchange = conversation.add(question, jpeg, listOf(summary, details).filter { it.isNotEmpty() }.joinToString("\n\n"))
        _state.value.pendingFrame?.let { frames[exchange.id] = it }
        _state.value.pendingFrameSource?.let { frameSources[exchange.id] = it }
        tag?.let { labels[exchange.id] = it }
        parts[exchange.id] = summary to details
        if (wasInterrupted) interrupted += exchange.id
        _state.update { it.copy(pendingQuestion = "", pendingFrame = null, pendingFrameSource = null, pendingAnswer = "", pendingDetails = "") }
        publishChat()
        touch()
        return exchange.id
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
        parts.clear()
        interrupted.clear()
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
            val (summary, details) = parts[exchange.id] ?: (exchange.answer to "")
            ChatItem(
                exchange.id,
                exchange.question,
                summary,
                frames[exchange.id],
                remembered = index >= start,
                model = labels[exchange.id],
                frameSource = frameSources[exchange.id],
                details = details,
                interrupted = exchange.id in interrupted,
            )
        }
        _state.update { it.copy(chat = chat) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    override fun onCleared() {
        audio.stop()
        headsetButtons.stop()
    }

    private companion object {
        const val IDLE_RESET_MS = 5 * 60 * 1000L
    }
}
