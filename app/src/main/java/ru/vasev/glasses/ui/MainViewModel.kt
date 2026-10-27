package ru.vasev.glasses.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.R
import ru.vasev.glasses.answer.SentenceSplitter
import ru.vasev.glasses.answer.SpokenAnswer
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.Shot
import ru.vasev.glasses.camera.PhoneCameraException
import ru.vasev.glasses.claude.AssistantClient
import ru.vasev.glasses.claude.AssistantMode
import ru.vasev.glasses.claude.AssistantTools
import ru.vasev.glasses.claude.ToolPlan
import ru.vasev.glasses.claude.Whereabouts
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
import ru.vasev.glasses.links.FoundLink
import ru.vasev.glasses.links.LinkInfo
import ru.vasev.glasses.links.LinkScanner
import ru.vasev.glasses.links.LinkTexts
import ru.vasev.glasses.llm.WebSource
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.nav.NavCommand
import ru.vasev.glasses.nav.NavCommands
import ru.vasev.glasses.nav.NavVocabulary
import ru.vasev.glasses.notes.Note
import ru.vasev.glasses.rates.CurrencySettings
import ru.vasev.glasses.profile.UserRole
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.MicChoice
import ru.vasev.glasses.speech.SpeechException
import ru.vasev.glasses.speech.Talker
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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
    /** Ссылки веб-поиска — под ответом, вслух не читаются. */
    val sources: List<WebSource> = emptyList(),
    /** Сохранённые и найденные заметки — карточками со снимком и «Проложить маршрут». */
    val notes: List<NoteCard> = emptyList(),
    /** Тексты удалённых в этом ответе заметок. */
    val deletedNotes: List<String> = emptyList(),
    /** «Переведи, что вижу»: перевод виден сразу, без «Подробнее». */
    val translation: Boolean = false,
    /** Дата курса ЦБ, по которому пересчитаны цены, и признак «сохранённый, без сети». */
    val ratesNote: RatesNote? = null,
    /** Ссылки и QR-коды на снимке этого вопроса. */
    val links: List<FoundLink> = emptyList(),
    /** Вопрос был текстом: ответ не звучал, на нём кнопка «Озвучить». */
    val silent: Boolean = false,
)

/** Заметка в чате: [saved] — только что сохранена, иначе найдена. */
data class NoteCard(val note: Note, val photo: Bitmap?, val saved: Boolean)

data class RatesNote(val date: LocalDate, val offline: Boolean)

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
    /** Пишется перевод снимка: полный текст виден сразу. */
    val pendingTranslation: Boolean = false,
    /** Ссылка на снимке, ждущая подтверждения: карточка с адресом, доменом и предупреждениями. */
    val confirmLink: FoundLink? = null,
    val error: String? = null,
    /** Текущий ход начат в «Камере»: его ответ — шторкой поверх снимка. */
    val cameraTurn: Boolean = false,
    /** Обмен, показанный шторкой в «Камере»; null — шторка закрыта. */
    val cameraSheetId: Long? = null,
    /** Снимок из ленты, о котором следующий вопрос в «Камере» (вместо нового кадра). */
    val selectedShot: Shot? = null,
    val selectedShotPreview: Bitmap? = null,
)

/** Откуда начат ход: от этого зависит, где показать ответ. */
enum class Origin { ASSISTANT, CAMERA }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val glasses = (app as GlassesApp).glasses
    private val speech = (app as GlassesApp).speechSource
    private val audio = (app as GlassesApp).audioOutput
    private val router = (app as GlassesApp).audioRouter
    private val headsetButtons = (app as GlassesApp).headsetButtons
    private val profile = (app as GlassesApp).profile
    private val llm = (app as GlassesApp).llm
    private val styleSettings = (app as GlassesApp).style
    private val assistant = AssistantClient(llm)
    private val abbreviations = SentenceSplitter.loadAbbreviations(app)

    // История живёт только в памяти: перезапуск приложения начинает разговор заново.
    private val conversation = Conversation()
    private val frames = mutableMapOf<Long, Bitmap>()
    private val labels = mutableMapOf<Long, ModelTag>()
    private val frameSources = mutableMapOf<Long, ImageSource>()
    private val parts = mutableMapOf<Long, Pair<String, String>>()
    private val interrupted = mutableSetOf<Long>()
    private val extras = mutableMapOf<Long, Extras>()
    private val shownQuestions = mutableMapOf<Long, String>()
    private val silentIds = mutableSetOf<Long>()

    /** Поиск ссылок и QR-кодов на текущем снимке (на телефоне, ML Kit). */
    private val linkScanner = LinkScanner()
    private var linkScan: Deferred<List<FoundLink>>? = null
    private var linkOffer: Job? = null

    /** Открыть ссылку — после подтверждения; открывает экран (нужен контекст Activity для Custom Tabs). */
    private val _openLink = MutableSharedFlow<LinkInfo>(extraBufferCapacity = 1)
    val openLink: SharedFlow<LinkInfo> = _openLink.asSharedFlow()

    /** Что показать под ответом, кроме текста. */
    private data class Extras(
        val sources: List<WebSource> = emptyList(),
        val notes: List<NoteCard> = emptyList(),
        val deletedNotes: List<String> = emptyList(),
        val translation: Boolean = false,
        val ratesNote: RatesNote? = null,
        val links: List<FoundLink> = emptyList(),
    )

    private val noteStore = (app as GlassesApp).notes
    private val rates = (app as GlassesApp).rates
    private val currency = (app as GlassesApp).currency
    private val location = (app as GlassesApp).location

    /** Все заметки — для экрана «Заметки». */
    val notes: StateFlow<List<Note>> = noteStore.notes

    /** Запрос разрешения на геопозицию (при первой заметке); подставляется из MainActivity. */
    var locationPermissionRequester: (suspend () -> Boolean)? = null
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

    /** Владелец жизненного цикла для разового снимка камерой телефона без видоискателя (Activity). */
    var cameraOwner: LifecycleOwner? = null

    /** Голосовые команды навигации: исполнитель и словарь задаёт Activity (там вкладка «Навигация»). */
    var navCommandHandler: (suspend (NavCommand) -> Unit)? = null
    var navVocabulary: (() -> NavVocabulary)? = null
    var navCommandDone: ((NavCommand) -> Unit)? = null

    /** Лента последних снимков «Камеры». */
    private val shotStore = (app as GlassesApp).shots
    val shots: StateFlow<List<Shot>> = shotStore.shots

    /** Индикатор очков: заряд и «хоть раз подключались». */
    val glassesBattery: StateFlow<Int?> = glasses.battery
    val glassesEverConnected: StateFlow<Boolean> = glasses.everConnected
    val glassesNotice: StateFlow<Int?> = glasses.notice

    fun dismissGlassesNotice() = glasses.dismissNotice()

    /** Выбор микрофона (шторка в «Камере»). */
    private val micSettings = (app as GlassesApp).micSettings
    val micChoice: StateFlow<MicChoice> = micSettings.choice
    fun setMicChoice(choice: MicChoice) = micSettings.set(choice)

    /** Метку модели у ответов показываем всегда только разработчику; остальным — когда ответила не основная модель. */
    val showAllModelLabels: StateFlow<Boolean> = profile.role.map { it == UserRole.DEVELOPER }
        .stateIn(viewModelScope, SharingStarted.Eagerly, profile.role.value == UserRole.DEVELOPER)

    private var job: Job? = null
    private var idleJob: Job? = null

    /** Разрешение на камеру телефона запрашивает MainActivity до вызова. */
    fun setImageSource(source: ImageSource) = imageSources.set(source)

    /**
     * «Спросить»: голос → модель → голос. Во время ответа это же — «перебить»: запрос и речь
     * останавливаются, и сразу начинается прослушивание нового вопроса.
     */
    fun ask() = run(withFrame = false)

    /** «Что я вижу»: снимок из выбранного источника и описание — голосом вопрос задавать не нужно. */
    fun whatDoISee() = run(withFrame = true, presetQuestion = L.s(R.string.what_do_i_see_question))

    /** «Переведи, что вижу» кнопкой: снимок → перевод всего текста на нём, без вопроса голосом. */
    fun translateWhatISee() = run(withFrame = true, presetQuestion = L.s(R.string.translate_view_question), mode = AssistantMode.TRANSLATE_VIEW)

    /** «Написать текстом»: ответ только на экране (печатают там, где говорить неудобно), «Озвучить» — по кнопке. */
    fun askText(text: String) {
        if (text.isBlank()) return
        run(withFrame = false, presetQuestion = text.trim(), silent = true)
    }

    /** «Озвучить» у ответа на вопрос текстом. */
    fun speakAnswer(id: Long) {
        val summary = parts[id]?.first ?: return
        job?.cancel()
        audio.stop()
        job = viewModelScope.launch {
            setPhase(Phase.SPEAKING)
            try {
                audio.speak(summary, LanguageSettings.current.value.locale, AudioChannel.ME)
            } finally {
                setPhase(Phase.IDLE)
            }
        }
    }

    // «Камера»: снимок — текущий кадр или выбранный в ленте; ответ — шторкой поверх снимка и в общей истории.

    /** Затвор, нажатие: описать, что на снимке. */
    fun cameraDescribe() = run(withFrame = true, presetQuestion = L.s(R.string.what_do_i_see_question), origin = Origin.CAMERA)

    /** Затвор, удержание: снимок и свой вопрос голосом. */
    fun cameraAsk() = run(withFrame = true, origin = Origin.CAMERA)

    fun cameraTranslate() =
        run(withFrame = true, presetQuestion = L.s(R.string.translate_view_question), mode = AssistantMode.TRANSLATE_VIEW, origin = Origin.CAMERA)

    /** «Спросить ещё» в шторке: следующий вопрос о том же снимке (он остался в разговоре картинкой). */
    fun cameraFollowUp() = run(withFrame = false, origin = Origin.CAMERA)

    fun closeCameraSheet() = _state.update { it.copy(cameraSheetId = null) }

    /** Плитка ленты: следующий вопрос — об этом снимке. Повторное нажатие — обратно к живому кадру. */
    fun selectShot(shot: Shot?) {
        if (shot == null || shot.id == _state.value.selectedShot?.id) {
            _state.update { it.copy(selectedShot = null, selectedShotPreview = null) }
            return
        }
        viewModelScope.launch {
            val bitmap = shotStore.jpeg(shot)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            _state.update { it.copy(selectedShot = shot, selectedShotPreview = bitmap, cameraSheetId = null) }
        }
    }

    suspend fun shotThumbnail(shot: Shot): Bitmap? = shotStore.thumbnail(shot)

    /** Снимок для видоискателя «Камеры» (последний кадр с очков). */
    suspend fun shotImage(shot: Shot): Bitmap? = shotStore.thumbnail(shot, side = 1024)

    fun deleteShot(shot: Shot) {
        if (_state.value.selectedShot?.id == shot.id) selectShot(null)
        viewModelScope.launch { shotStore.delete(shot) }
    }

    /** Копия снимка в галерею телефона; результат — для подсказки на экране. */
    suspend fun saveShotToGallery(shot: Shot): Boolean = shotStore.saveToGallery(shot)

    /** Первая плитка ленты: фото из галереи становится выбранным снимком (без вопроса — его задают кнопками). */
    fun pickFromGallery() {
        viewModelScope.launch {
            val uri = galleryPicker?.invoke() ?: return@launch
            val resolver = getApplication<Application>().contentResolver
            val jpeg = try {
                withContext(Dispatchers.Default) { ImagePrep.fromUri(resolver, uri) }
            } catch (e: Exception) {
                Log.w(TAG, "Gallery photo failed: ${e.javaClass.simpleName}")
                return@launch
            }
            selectShot(shotStore.add(jpeg, ImageSource.GALLERY))
        }
    }

    /**
     * «Запомни» в «Камере»: снимок, вопрос «Что запомнить?» и ответ голосом — текст заметки. Молчание —
     * заметка без текста: снимок, время и место. Модель не участвует.
     */
    fun cameraRemember() {
        linkOffer?.cancel()
        job?.cancel()
        audio.stop()
        job = viewModelScope.launch {
            val language = LanguageSettings.current.value
            _state.update { it.copy(cameraTurn = true, cameraSheetId = null, pendingQuestion = "", pendingAnswer = "", pendingDetails = "", error = null) }
            try {
                val jpeg = captureFrame(Origin.CAMERA) ?: return@launch
                val place = viewModelScope.async { noteLocation() }
                setPhase(Phase.SPEAKING)
                audio.speak(L.s(R.string.say_what_to_remember), language.locale, AudioChannel.ME)
                // Защита от эха, как у «Открыть?»: вопрос не должен попасть в текст заметки.
                delay(ECHO_GUARD_MS)
                setPhase(Phase.LISTENING)
                val text = withTimeoutOrNull(REMEMBER_LISTEN_MS) {
                    try {
                        speech.listen(
                            language.speechTag,
                            onPartial = { partial -> _state.update { it.copy(pendingQuestion = partial) } },
                            onLevel = { _micLevel.value = it },
                        ).text
                    } catch (e: SpeechException) {
                        if (e.noSpeech || e.transient) "" else throw e
                    }
                }.orEmpty().trim()
                _micLevel.value = 0f
                // Почти чёрный кадр с камеры телефона (в кармане) к заметке не прикрепляется.
                val photo = jpeg.takeUnless { imageSources.source.value == ImageSource.PHONE_CAMERA && ImagePrep.isDark(it) }
                val note = noteStore.add(text, photo, place.await())
                Log.d(TAG, "Notes: saved from camera text=${text.isNotEmpty()} photo=${photo != null} location=${note.hasLocation}")
                val answerText = L.s(if (text.isEmpty()) R.string.note_saved_photo_only else R.string.note_saved_spoken)
                val question = if (text.isEmpty()) L.s(R.string.remember_question_photo) else L.s(R.string.remember_question, text)
                val card = NoteCard(note, noteStore.photo(note, NOTE_PREVIEW_SIDE), saved = true)
                commit(
                    question, question, jpeg, answerText, "", refused = false, tag = null, wasInterrupted = false,
                    extra = Extras(notes = listOf(card)), origin = Origin.CAMERA, silent = false,
                )
                setPhase(Phase.SPEAKING)
                audio.speak(answerText, language.locale, AudioChannel.ME)
                setPhase(Phase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remember failed: ${e.javaClass.simpleName}")
                failAloud(e.message ?: L.s(R.string.something_went_wrong))
            } finally {
                _state.update { it.copy(cameraTurn = false) }
            }
        }
    }

    /** Только для debug-сборок: прогон сценария с готовым текстом вместо распознавания речи. */
    fun debugAsk(question: String, withFrame: Boolean) = run(withFrame, presetQuestion = question)

    /** Только debug: следующий снимок — из файла на телефоне вместо выбранного источника (проверка QR и ссылок). */
    var debugFrameFile: java.io.File? = null

    /** Экран «Заметки»: удалить заметку вместе со снимком. */
    fun deleteNote(id: String) {
        viewModelScope.launch { noteStore.delete(listOf(id)) }
    }

    /** Снимок заметки для экрана «Заметки» и полноэкранного просмотра. */
    suspend fun notePhoto(note: Note, maxSide: Int = 1024): Bitmap? = noteStore.photo(note, maxSide)

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

    private fun run(
        withFrame: Boolean,
        presetQuestion: String? = null,
        mode: AssistantMode = AssistantMode.CHAT,
        silent: Boolean = false,
        origin: Origin = Origin.ASSISTANT,
    ) {
        if (_state.value.phase.interruptible) Log.d(TAG, "Assistant: answer interrupted, listening for a new question")
        linkOffer?.cancel()
        job?.cancel()
        audio.stop()
        job = viewModelScope.launch {
            linkScan?.cancel()
            linkScan = null
            _state.update { it.copy(confirmLink = null) }
            if (lastActivity != 0L && SystemClock.elapsedRealtime() - lastActivity >= IDLE_RESET_MS) {
                resetConversation()
            }
            _state.update {
                it.copy(
                    pendingQuestion = "", pendingFrame = null, pendingFrameSource = null, pendingAnswer = "", pendingDetails = "", error = null,
                    cameraTurn = origin == Origin.CAMERA, cameraSheetId = if (origin == Origin.CAMERA) null else it.cameraSheetId,
                )
            }
            // Язык общения берётся на каждый вопрос: смена языка в настройках действует со следующего вопроса.
            val language = LanguageSettings.current.value
            try {
                var jpeg: ByteArray? = null
                if (withFrame) {
                    jpeg = captureFrame(origin) ?: return@launch
                }

                // Конец моей фразы — для замера «конец фразы → первый звук»; при debug-запуске и по кнопке — момент нажатия.
                var endOfSpeechAt = SystemClock.elapsedRealtime()
                val question = presetQuestion ?: run {
                    setPhase(Phase.LISTENING)
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

                // Команды навигации («веди в отель», «как вернуться», «запомни, это мой отель»…) — до модели.
                val navCommand = navCommandHandler?.let { NavCommands.parse(question, navVocabulary?.invoke() ?: return@let null) }
                if (navCommand != null) {
                    setPhase(Phase.SPEAKING)
                    navCommandHandler?.invoke(navCommand)
                    _state.update { it.copy(pendingQuestion = "") }
                    setPhase(Phase.IDLE)
                    // Вкладка — только после хода: смена вкладки в одном кадре с изменением фазы роняла размытие (Haze).
                    navCommandDone?.invoke(navCommand)
                    return@launch
                }

                var turnMode = mode
                if (mode == AssistantMode.CHAT && Conversation.isCommand(question, L.array(R.array.translate_view_commands))) {
                    // «Переведи, что вижу» голосом: снимок — сейчас, если его ещё нет.
                    turnMode = AssistantMode.TRANSLATE_VIEW
                    if (jpeg == null) jpeg = captureFrame(origin) ?: return@launch
                } else if (jpeg == null && Conversation.isCommand(question, L.array(R.array.what_do_i_see_commands))) {
                    // «Что я вижу» голосом — тот же снимок с описанием, что и кнопка.
                    jpeg = captureFrame(origin) ?: return@launch
                }

                val links = answer(question, jpeg, language, TurnMetrics(endOfSpeechAt), turnMode, silent, origin)
                if (links.isNotEmpty() && !silent) offerLinks(links, language)
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
     * Снимок из выбранного источника с превью в чате; null — снимка не будет (очки не подключены — об этом уже
     * сказано вслух; фото из галереи не выбрано).
     */
    private suspend fun captureFrame(origin: Origin = Origin.ASSISTANT): ByteArray? {
        // «Камера» с выбранным в ленте снимком: вопрос о нём, нового кадра нет.
        val selected = _state.value.selectedShot
        if (origin == Origin.CAMERA && selected != null) {
            val jpeg = shotStore.jpeg(selected)
            if (jpeg != null) {
                _state.update { it.copy(pendingFrame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size), pendingFrameSource = selected.source) }
                linkScan = viewModelScope.async { linkScanner.scan(jpeg) }
                return jpeg
            }
        }
        val jpeg = takeFrame() ?: return null
        // Каждый снимок — в ленту «Камеры» (только на телефоне, последние 30).
        shotStore.add(jpeg, _state.value.pendingFrameSource ?: imageSources.source.value)
        return jpeg
    }

    private suspend fun takeFrame(): ByteArray? {
        debugFrameFile?.let { file ->
            debugFrameFile = null
            setPhase(Phase.CAPTURING)
            val resolver = getApplication<Application>().contentResolver
            val jpeg = withContext(Dispatchers.Default) { ImagePrep.fromUri(resolver, Uri.fromFile(file)) }
            _state.update { it.copy(pendingFrame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size), pendingFrameSource = ImageSource.GALLERY) }
            linkScan = viewModelScope.async { linkScanner.scan(jpeg) }
            return jpeg
        }
        val source = imageSources.source.value
        setPhase(Phase.CAPTURING)
        val jpeg = when (source) {
            ImageSource.GLASSES, ImageSource.SIMULATOR -> {
                if (!glasses.status.value.ready) {
                    // Автоматически на телефон не переключаем: он может лежать в кармане.
                    failAloud(L.s(if (source == ImageSource.GLASSES) R.string.glasses_not_connected else R.string.simulator_not_ready))
                    return null
                }
                glasses.captureJpeg(cameraPermissionRequester ?: { false })
            }
            // Видоискателя на экране нет («Ассистент») — камера включается на один снимок.
            ImageSource.PHONE_CAMERA -> cameraOwner?.let { phoneCamera.captureWithoutPreview(it) } ?: phoneCamera.capture()
            ImageSource.GALLERY -> {
                val uri = galleryPicker?.invoke()
                if (uri == null) {
                    setPhase(Phase.IDLE)
                    return null
                }
                val resolver = getApplication<Application>().contentResolver
                withContext(Dispatchers.Default) { ImagePrep.fromUri(resolver, uri) }
            }
        }
        val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        _state.update { it.copy(pendingFrame = preview, pendingFrameSource = source) }
        // QR-коды и напечатанные адреса — на телефоне, пока пользователь договаривает вопрос.
        linkScan = viewModelScope.async { linkScanner.scan(jpeg) }
        return jpeg
    }

    /**
     * Снимок для заметки «Запомни»: уже снятый для этого вопроса или новый из выбранного источника, если он доступен
     * без участия пользователя (галерея — нет). Почти чёрный кадр с камеры телефона (телефон в кармане) не прикрепляется.
     */
    private suspend fun notePhoto(current: ByteArray?): ByteArray? {
        if (current != null) return current
        return try {
            when (imageSources.source.value) {
                ImageSource.GLASSES, ImageSource.SIMULATOR ->
                    if (glasses.status.value.ready) glasses.captureJpeg(cameraPermissionRequester ?: { false }) else null
                ImageSource.PHONE_CAMERA -> (cameraOwner?.let { phoneCamera.captureWithoutPreview(it) } ?: phoneCamera.capture()).takeUnless { jpeg ->
                    ImagePrep.isDark(jpeg).also { if (it) Log.d(TAG, "Notes: phone camera frame is almost black, not attached") }
                }
                ImageSource.GALLERY -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Notes: no photo (${e.javaClass.simpleName})")
            null
        }
    }

    /** Координаты для заметки; разрешение спрашивается при первой заметке. */
    private suspend fun noteLocation(): DeviceLocation.Fix? {
        if (!location.granted && locationPermissionRequester?.invoke() != true) return null
        return location.current()
    }

    /** Страна и часовой пояс: для поиска — быстро (сеть или регион телефона), для валюты на ценниках — ещё и по GPS. */
    private suspend fun whereabouts(mode: AssistantMode): Whereabouts {
        val country = when (mode) {
            AssistantMode.CHAT -> location.networkCountry() ?: Locale.getDefault().country.takeIf { it.length == 2 }
            AssistantMode.TRANSLATE_VIEW -> location.country()
        }
        return Whereabouts(country, ZoneId.systemDefault().id, country?.let(CurrencySettings::forCountry))
    }

    /**
     * Ответ потоком: каждое законченное предложение сути сразу встаёт в очередь озвучки, подробности идут
     * только в чат. Пока ответ идёт, кнопка гарнитуры (и, если включено, голосовое «стоп») его прерывают,
     * а музыка других приложений приглушена.
     */
    /** Возвращает ссылки, найденные на снимке, — после ответа о них спросят голосом. */
    private suspend fun answer(
        question: String,
        jpeg: ByteArray?,
        language: AppLanguage,
        metrics: TurnMetrics,
        mode: AssistantMode,
        silent: Boolean = false,
        origin: Origin = Origin.ASSISTANT,
    ): List<FoundLink> {
        _state.update { it.copy(phase = Phase.THINKING, pendingTranslation = mode == AssistantMode.TRANSLATE_VIEW) }
        val plan = ToolPlan.of(mode, question, L.array(R.array.delete_note_triggers))
        val myCurrency = currency.currency.value
        val where = whereabouts(mode)
        Log.d(TAG, "Assistant: mode=$mode web=${plan.webSearch} notes=${plan.notes} delete=${plan.deleteNotes} country=${where.country}")
        // Ссылки на снимке ищутся на телефоне параллельно с прослушиванием вопроса; модели — только тип и домен.
        val found = linkScan?.await().orEmpty()
        val asked = LinkTexts.forModel(found)?.let { "$question\n\n$it" } ?: question
        val spoken = SpokenAnswer(SentenceSplitter(abbreviations))
        val sentences = Channel<String>(Channel.UNLIMITED)
        var streamed: StreamedAnswer? = null
        var committedId: Long? = null
        var finished = false
        var wasCancelled = false
        var tools: AssistantTools? = null

        // Вопрос текстом — ответ только на экране: ни звука, ни приглушения музыки, ни кнопки гарнитуры.
        if (!silent) {
            audio.holdFocus()
            headsetButtons.listen {
                Log.d(TAG, "Assistant: interrupted by headset button")
                ask()
            }
        }
        try {
            coroutineScope {
                // Голосовое «стоп»: микрофон гарнитуры включается параллельно с запросом к модели,
                // чтобы первая фраза уже звучала по тому же маршруту (без переключения посреди ответа).
                val micHeld = if (!silent && profile.voiceStop.value && router.headset.value?.mic != null) async { router.holdMic() } else null
                val voiceStop = launch {
                    if (micHeld?.await() == true) listenForStop(language)
                }
                val speaker = launch {
                    if (silent) return@launch
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

                // «Запомни, …»: снимок и координаты начинаем получать сразу, параллельно с запросом к модели.
                val remember = plan.notes && ToolPlan.startsWithAny(question, L.array(R.array.remember_triggers))
                val earlyPhoto = if (remember) async { notePhoto(jpeg) } else null
                val earlyLocation = if (remember) async { noteLocation() } else null
                tools = AssistantTools(
                    plan,
                    noteStore,
                    rates,
                    myCurrency,
                    notePhoto = { earlyPhoto?.await() ?: if (earlyPhoto == null) notePhoto(jpeg) else null },
                    noteLocation = { earlyLocation?.await() ?: if (earlyLocation == null) noteLocation() else null },
                )

                val result = assistant.stream(
                    mode,
                    plan,
                    tools?.toolSet(),
                    conversation.turnsFor(asked, jpeg),
                    language,
                    myCurrency,
                    where,
                    styleSettings.current.value,
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
                val extra = extrasOf(tools, mode, result.answer.sources, found)
                val refused = result.answer.text == null
                committedId = commit(
                    question, asked, jpeg, summaryOf(spoken, refused), if (refused) "" else spoken.detailsText, refused,
                    result.answer.tag, wasInterrupted = false, extra, origin, silent,
                )
                speaker.join()
                voiceStop.cancel()
                // Снимок и координаты для заметки, которую модель так и не сохранила, больше не нужны.
                earlyPhoto?.cancel()
                earlyLocation?.cancel()
            }
            finished = true
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } finally {
            sentences.close()
            if (!silent) {
                headsetButtons.stop()
                router.unholdMic()
                audio.releaseFocus()
            }
            if (!finished) {
                // Перебили или ответ оборвался: в чат и в память — то, что успело прийти.
                val id = committedId ?: if (spoken.fullText.isNotEmpty()) {
                    // Ответ не дописан — метка по выбранной модели (запасная к этому моменту ещё не включалась).
                    val tag = streamed?.answer?.tag ?: ModelTag(model.value.title, viaFallback = false, autoVision = false)
                    // Заметки могли сохраниться и до того, как ответ перебили, — их карточки тоже нужны.
                    val extra = withContext(NonCancellable) { extrasOf(tools, mode, streamed?.answer?.sources.orEmpty(), found) }
                    commit(
                        question, asked, jpeg, spoken.summaryText, spoken.detailsText, refused = false, tag = tag,
                        wasInterrupted = wasCancelled, extra = extra, origin = origin, silent = silent,
                    )
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
        return found
    }

    /**
     * После ответа — о ссылках на снимке: одна ссылка — карточка подтверждения и вопрос голосом; несколько — их число,
     * выбор на телефоне. Голосом («открыть», 10 с) — только ссылка без единого предупреждения, остальное — кнопкой.
     */
    private suspend fun offerLinks(links: List<FoundLink>, language: AppLanguage) {
        val single = links.singleOrNull()
        if (single != null) _state.update { it.copy(confirmLink = single) }
        speakAboutLink(links, language)
    }

    /** Карточка ссылки из чата: нажатие — показать проверку и спросить голосом (сама ссылка не открывается). */
    fun requestLink(link: FoundLink) {
        if (_state.value.phase != Phase.IDLE) {
            _state.update { it.copy(confirmLink = link) }
            return
        }
        linkOffer?.cancel()
        _state.update { it.copy(confirmLink = link) }
        linkOffer = viewModelScope.launch {
            try {
                speakAboutLink(listOf(link), LanguageSettings.current.value)
            } finally {
                setPhase(Phase.IDLE)
            }
        }
    }

    /** Кнопка «Открыть» на карточке — подтверждение для любой ссылки, которую вообще можно открыть. */
    fun confirmLink(link: FoundLink) {
        linkOffer?.cancel()
        audio.stop()
        _state.update { it.copy(confirmLink = null) }
        if (link.info.openable) _openLink.tryEmit(link.info)
    }

    fun dismissLink() {
        linkOffer?.cancel()
        audio.stop()
        _state.update { it.copy(confirmLink = null) }
    }

    private suspend fun speakAboutLink(links: List<FoundLink>, language: AppLanguage) {
        setPhase(Phase.SPEAKING)
        audio.speak(LinkTexts.spoken(L.context(language), links), language.locale, AudioChannel.ME)
        val link = links.singleOrNull() ?: return
        if (!link.info.voiceConfirmAllowed) return
        // Защита от эха: вопрос кончается словом «Открыть?», а onDone синтеза приходит раньше, чем звук доиграет
        // в динамике, — без паузы распознаватель слышал собственный вопрос и открывал ссылку сам (проверено на S25).
        delay(ECHO_GUARD_MS)
        setPhase(Phase.LISTENING)
        val commands = L.array(R.array.open_commands)
        // Тишину и временные сбои распознавателя пережидаем до конца 10 с; первая услышанная фраза — ответ.
        val confirmed = withTimeoutOrNull(VOICE_CONFIRM_MS) {
            var phrase: String? = null
            while (phrase == null) {
                phrase = try {
                    speech.listen(language.speechTag, onLevel = { _micLevel.value = it }).text
                } catch (e: SpeechException) {
                    if (e.noSpeech || e.transient) null else ""
                }
            }
            Conversation.isCommand(phrase, commands).also { Log.d(TAG, "Links: voice reply matched=$it") }
        } == true
        _micLevel.value = 0f
        // Карточку могли закрыть, пока слушали, — тогда ничего не открываем.
        if (confirmed && _state.value.confirmLink == link) {
            Log.d(TAG, "Links: opened by voice")
            _state.update { it.copy(confirmLink = null) }
            _openLink.tryEmit(link.info)
            setPhase(Phase.SPEAKING)
            audio.speak(L.s(R.string.say_link_opening), language.locale, AudioChannel.ME)
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

    /**
     * Записывает обмен в память разговора и чат; возвращает его id. [asked] — вопрос, как его получила модель
     * (с пометкой о ссылках на снимке), [question] — как его показать в чате.
     */
    private fun commit(
        question: String,
        asked: String,
        jpeg: ByteArray?,
        summary: String,
        details: String,
        refused: Boolean,
        tag: ModelTag?,
        wasInterrupted: Boolean,
        extra: Extras,
        origin: Origin = Origin.ASSISTANT,
        silent: Boolean = false,
    ): Long {
        // В память — ответ целиком, без маркера: так модель помнит и подробности.
        val exchange = conversation.add(asked, jpeg, listOf(summary, details).filter { it.isNotEmpty() }.joinToString("\n\n"))
        _state.value.pendingFrame?.let { frames[exchange.id] = it }
        _state.value.pendingFrameSource?.let { frameSources[exchange.id] = it }
        tag?.let { labels[exchange.id] = it }
        parts[exchange.id] = summary to details
        extras[exchange.id] = extra
        if (asked != question) shownQuestions[exchange.id] = question
        if (wasInterrupted) interrupted += exchange.id
        if (silent) silentIds += exchange.id
        _state.update {
            it.copy(
                pendingQuestion = "", pendingFrame = null, pendingFrameSource = null, pendingAnswer = "", pendingDetails = "",
                // Ответ из «Камеры» — шторкой поверх снимка (и в общей истории «Ассистента»).
                cameraSheetId = if (origin == Origin.CAMERA) exchange.id else it.cameraSheetId,
            )
        }
        publishChat()
        touch()
        return exchange.id
    }

    private fun summaryOf(spoken: SpokenAnswer, refused: Boolean): String = if (refused) L.s(R.string.say_cannot_answer) else spoken.summaryText

    /** Ссылки, карточки заметок (со снимками), удалённые заметки и курс ЦБ — для показа под ответом. */
    private suspend fun extrasOf(tools: AssistantTools?, mode: AssistantMode, sources: List<WebSource>, links: List<FoundLink>): Extras {
        val cards = tools?.let { t ->
            t.saved.map { NoteCard(it, noteStore.photo(it, NOTE_PREVIEW_SIDE), saved = true) } +
                t.found.filter { f -> t.saved.none { it.id == f.id } }.take(MAX_FOUND_CARDS)
                    .map { NoteCard(it, noteStore.photo(it, NOTE_PREVIEW_SIDE), saved = false) }
        }.orEmpty()
        return Extras(
            sources = sources,
            notes = cards,
            deletedNotes = tools?.deleted?.map { it.text }.orEmpty(),
            translation = mode == AssistantMode.TRANSLATE_VIEW,
            ratesNote = tools?.ratesUsed?.let { RatesNote(it.table.date, it.offline) },
            links = links,
        )
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
        extras.clear()
        shownQuestions.clear()
        silentIds.clear()
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
            val extra = extras[exchange.id] ?: Extras()
            ChatItem(
                exchange.id,
                shownQuestions[exchange.id] ?: exchange.question,
                summary,
                frames[exchange.id],
                remembered = index >= start,
                model = labels[exchange.id],
                frameSource = frameSources[exchange.id],
                details = details,
                interrupted = exchange.id in interrupted,
                sources = extra.sources,
                notes = extra.notes,
                deletedNotes = extra.deletedNotes,
                translation = extra.translation,
                ratesNote = extra.ratesNote,
                links = extra.links,
                silent = exchange.id in silentIds,
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
        const val VOICE_CONFIRM_MS = 10_000L
        const val REMEMBER_LISTEN_MS = 12_000L
        const val ECHO_GUARD_MS = 1_000L
        const val NOTE_PREVIEW_SIDE = 640
        const val MAX_FOUND_CARDS = 3
    }
}
