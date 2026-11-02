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
import ru.vasev.glasses.links.LinkCheck
import ru.vasev.glasses.camera.ImagePrep
import ru.vasev.glasses.chats.ChatMeta
import ru.vasev.glasses.chats.ChatRules
import ru.vasev.glasses.chats.LinkRef
import ru.vasev.glasses.chats.NoteRef
import ru.vasev.glasses.chats.StoredExchange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import ru.vasev.glasses.profile.AssistantProfile
import ru.vasev.glasses.profile.Skill
import ru.vasev.glasses.claude.CalendarEvent
import ru.vasev.glasses.translate.Line
import ru.vasev.glasses.translate.Who
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.Beeps
import ru.vasev.glasses.speech.Headset
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
    /** Не обмен, а тонкий разделитель в ленте: «— Профиль: Инженер —». */
    val divider: String? = null,
    /** Не обмен, а реплика «Переводчика». */
    val translated: TranslatedLine? = null,
)

/** Реплика «Переводчика» в ленте: [who] — собеседник или я; [translation] null — перевод недоступен. */
data class TranslatedLine(val who: Who, val original: String, val translation: String?, val model: ModelTag?)

/** Вставка в ленту между обменами: разделитель или реплика перевода. */
private sealed interface ChatInsert {
    data class Divider(val text: String) : ChatInsert
    data class Translated(val line: TranslatedLine) : ChatInsert
}

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
    private val gestures = (app as GlassesApp).gestures
    private val gestureSettings = (app as GlassesApp).gestureSettings
    private val profile = (app as GlassesApp).profile
    private val llm = (app as GlassesApp).llm
    private val styleSettings = (app as GlassesApp).style
    private val skills = (app as GlassesApp).skills
    private val translatorSettings = (app as GlassesApp).translator
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

    /** Вставки в ленту (разделители и реплики перевода): сколько обменов перед ней — вставка. */
    private val inserts = mutableListOf<Pair<Int, ChatInsert>>()

    /** Поиск ссылок и QR-кодов на текущем снимке (на телефоне, ML Kit). */
    private val linkScanner = LinkScanner()
    private var linkScan: Deferred<List<FoundLink>>? = null
    private var linkOffer: Job? = null

    /** Событие для календаря: Activity открывает системное окно, сохраняет его пользователь. */
    private val _calendarEvents = MutableSharedFlow<CalendarEvent>(extraBufferCapacity = 2)
    val calendarEvents: SharedFlow<CalendarEvent> = _calendarEvents.asSharedFlow()

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
    /** Время последнего обмена (часы телефона): после 2 часов тишины следующий вопрос начинает новый чат. */
    private var lastActivity = 0L

    /** История чатов на телефоне. */
    private val chatStore = (app as GlassesApp).chats
    val chats: StateFlow<List<ChatMeta>> = chatStore.chats

    /** Открытый чат. Новый объект — новый чат: сохранения уже начатых обменов уходят в свой чат. */
    private class OpenChat(var id: String?)
    private var chat = OpenChat(null)
    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()

    /** Сохранения — строго по очереди: первый обмен создаёт чат, следующие дописываются в него. */
    private val saveLock = Mutex()

    /** Чат на экране (приложение открыто, «Ассистент», не настройки): иначе ответ помечается непрочитанным. */
    private var viewing = false

    /** Следующий ход начат голосом через Meta AI — чат «с очков». */
    private var nextFromGlasses = false
    private var turnFromGlasses = false

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

    /** Подключённые гарнитуры (очки — первыми): на чипе «Микрофон» видно, чей микрофон слушает. */
    val headsets: StateFlow<List<Headset>> = router.headsets

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
    fun ask() = run(withFrame = _attachment.value != null)

    /**
     * «Hey Meta, start Sputnik»: сразу слушать вопрос (идущий ответ прерывается, как кнопкой).
     * false — ответить нечем (нет ключа для модели ассистента): об этом сказано вслух.
     */
    fun voiceLaunch(): Boolean {
        if (hasApiKey.value) {
            nextFromGlasses = true
            ask()
            return true
        }
        noKeyAloud()
        return false
    }

    /**
     * Вопрос жестом на дужке: сигнал «слушаю» и сразу микрофон. Речи нет 5 с — тихая отмена с сигналом «отбой»,
     * запроса к модели нет (очки поправили, а не позвали ассистента).
     */
    fun gestureAsk() {
        if (hasApiKey.value) run(withFrame = false, byGesture = true) else noKeyAloud()
    }

    private fun noKeyAloud() {
        job?.cancel()
        audio.stop()
        job = viewModelScope.launch { failAloud(L.s(R.string.say_voice_launch_no_key)) }
    }

    /** «Что я вижу»: снимок из выбранного источника и описание — голосом вопрос задавать не нужно. */
    fun whatDoISee() = run(withFrame = true, presetQuestion = L.s(R.string.what_do_i_see_question))

    /** «Переведи, что вижу» кнопкой: снимок → перевод всего текста на нём, без вопроса голосом. */
    fun translateWhatISee() = run(withFrame = true, presetQuestion = L.s(R.string.translate_view_question), mode = AssistantMode.TRANSLATE_VIEW)

    /** Вопрос текстом из поля ввода: ответ только на экране, «Озвучить» — по кнопке. Снимок в поле — уходит с вопросом. */
    fun askText(text: String) {
        if (text.isBlank()) return
        run(withFrame = _attachment.value != null, presetQuestion = text.trim(), silent = true)
    }

    /** Снимок в поле ввода: с ним уходит следующий вопрос (текстом или голосом) или действие над ним. */
    class Attachment(val jpeg: ByteArray, val preview: Bitmap, val source: ImageSource)

    private val _attachment = MutableStateFlow<Attachment?>(null)
    val attachment: StateFlow<Attachment?> = _attachment.asStateFlow()

    /**
     * Снять или выбрать снимок для поля ввода: камера телефона (из видоискателя — он уже на экране), галерея,
     * очки (DAT, снимок по запросу приложения: кнопка съёмки на очках до приложения на Android не доходит).
     */
    fun attach(source: ImageSource): Job {
        job?.cancel()
        audio.stop()
        return viewModelScope.launch {
            setPhase(Phase.CAPTURING)
            try {
                val jpeg = when (source) {
                    ImageSource.PHONE_CAMERA -> phoneCamera.capture()
                    ImageSource.GALLERY -> {
                        val uri = galleryPicker?.invoke() ?: return@launch setPhase(Phase.IDLE)
                        val resolver = getApplication<Application>().contentResolver
                        withContext(Dispatchers.Default) { ImagePrep.fromUri(resolver, uri) }
                    }
                    ImageSource.GLASSES, ImageSource.SIMULATOR -> {
                        if (!glasses.status.value.ready) {
                            failAloud(L.s(if (source == ImageSource.GLASSES) R.string.glasses_not_connected else R.string.simulator_not_ready))
                            return@launch
                        }
                        glasses.captureJpeg(cameraPermissionRequester ?: { false })
                    }
                }
                shotStore.add(jpeg, source)
                _attachment.value = Attachment(jpeg, BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size), source)
                Log.d(TAG, "Composer: attached photo from $source")
                setPhase(Phase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GlassesException) {
                failAloud(e.message.orEmpty())
            } catch (e: PhoneCameraException) {
                failAloud(e.message.orEmpty())
            }
        }.also { job = it }
    }

    /** Активный профиль из чипа шапки; смена — разделитель в ленте («— Профиль: Инженер —»). */
    fun setProfile(profile: AssistantProfile?) {
        if (skills.active.value == profile && !translatorSettings.profile.value) return
        translatorSettings.setProfile(false)
        skills.setActive(profile)
        val title = profile?.let { L.s(it.titleRes) } ?: L.s(R.string.profile_none)
        Log.d(TAG, "Profile: ${profile?.name ?: "none"}")
        addDivider(L.s(R.string.divider_profile, title))
    }

    /** Профиль «Переводчик» в чипе: перевод включён, кнопка перевода в шапке скрыта. */
    fun setTranslatorProfile() {
        if (translatorSettings.profile.value) return
        skills.setActive(null)
        translatorSettings.setProfile(true)
        Log.d(TAG, "Profile: TRANSLATOR")
        addDivider(L.s(R.string.divider_profile, L.s(R.string.skill_translator)))
    }

    /** Разделитель в ленте и в истории чата; в пустом чате не нужен. */
    private fun addDivider(text: String) {
        if (conversation.isEmpty && inserts.isEmpty()) return
        inserts += conversation.exchanges.size to ChatInsert.Divider(text)
        publishChat()
        save(StoredExchange(asked = "", summary = "", divider = text), null)
    }

    /** Реплика «Переводчика» — в ленту и в историю чата (в память модели не идёт). */
    fun addTranslatedLine(line: Line, model: ModelTag?) {
        inserts += conversation.exchanges.size to ChatInsert.Translated(TranslatedLine(line.who, line.original, line.translation, model))
        publishChat()
        touch()
        save(StoredExchange(asked = line.original, summary = line.translation.orEmpty(), model = model, lineWho = line.who.name), null)
    }

    /** Перевод включили или выключили (кнопкой или профилем) — разделитель в ленте. */
    fun translationToggled(on: Boolean) {
        val profile = skills.active.value?.let { L.s(it.titleRes) } ?: L.s(R.string.profile_none)
        addDivider(L.s(if (on) R.string.divider_translate_on else R.string.divider_translate_off, profile))
    }

    /** «Снять очками»: очки, а в debug с выбранным симулятором — симулятор. */
    fun glassesSource(): ImageSource = if (imageSources.source.value == ImageSource.SIMULATOR) ImageSource.SIMULATOR else ImageSource.GLASSES

    /** Снимок из «Медиа» — в поле ввода чата («В чат»): с ним уйдёт следующий вопрос или действие. */
    fun attachJpeg(jpeg: ByteArray, source: ImageSource?) {
        val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        _attachment.value = Attachment(jpeg, preview, source ?: ImageSource.GALLERY)
        Log.d(TAG, "Composer: attached photo from media")
    }

    fun clearAttachment() {
        _attachment.value = null
    }

    /** Действия над снимком в поле ввода — то, что раньше делали кнопки вкладки «Камера». */
    fun describeAttachment() = run(withFrame = true, presetQuestion = L.s(R.string.what_do_i_see_question))

    fun translateAttachment() =
        run(withFrame = true, presetQuestion = L.s(R.string.translate_view_question), mode = AssistantMode.TRANSLATE_VIEW)

    fun rememberAttachment() = cameraRemember(Origin.ASSISTANT)

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
    fun cameraRemember(origin: Origin = Origin.CAMERA) {
        linkOffer?.cancel()
        job?.cancel()
        audio.stop()
        job = viewModelScope.launch {
            val language = LanguageSettings.current.value
            _state.update { it.copy(cameraTurn = origin == Origin.CAMERA, cameraSheetId = null, pendingQuestion = "", pendingAnswer = "", pendingDetails = "", error = null) }
            try {
                val jpeg = captureFrame(origin) ?: return@launch
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
                    extra = Extras(notes = listOf(card)), origin = origin, silent = false,
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

    /** «Новый чат»: прежний остаётся в истории, следующий вопрос начнёт новый. */
    fun newConversation() {
        stop()
        startNewChat()
    }

    private fun startNewChat() {
        resetConversation()
        chat = OpenChat(null)
        _currentChatId.value = null
    }

    /** Открыть чат из истории: переписка на экран, хвост — снова в память модели. */
    fun openChat(id: String) {
        if (id == chat.id) return
        stop()
        val meta = chatStore.chats.value.firstOrNull { it.id == id } ?: return
        job = viewModelScope.launch {
            val records = chatStore.load(id)
            resetConversation()
            chat = OpenChat(id)
            _currentChatId.value = id
            restore(records)
            lastActivity = meta.updated
            if (viewing) chatStore.markRead(id)
            Log.d(TAG, "Chats: opened $id, exchanges=${records.size}")
        }
    }

    /** Удалить чат (переписку; снимки остаются в «Медиа»). Открытый — сменяется новым. */
    fun deleteChat(id: String) {
        if (id == chat.id) newConversation()
        viewModelScope.launch { chatStore.delete(id) }
    }

    /** Чат на экране или нет — для отметки «непрочитано». */
    fun setViewing(value: Boolean) {
        viewing = value
        val id = chat.id ?: return
        if (value) viewModelScope.launch { chatStore.markRead(id) }
    }

    /** Переписка из файла — в память модели (снимки — только у последних [RESTORE_PHOTOS] обменов) и на экран. */
    private suspend fun restore(records: List<StoredExchange>) {
        val notesById = noteStore.notes.value.associateBy { it.id }
        records.forEachIndexed { index, r ->
            if (r.divider != null) {
                inserts += conversation.exchanges.size to ChatInsert.Divider(r.divider)
                return@forEachIndexed
            }
            if (r.lineWho != null) {
                val who = Who.entries.firstOrNull { it.name == r.lineWho } ?: Who.PARTNER
                inserts += conversation.exchanges.size to ChatInsert.Translated(TranslatedLine(who, r.asked, r.summary.ifEmpty { null }, r.model))
                return@forEachIndexed
            }
            val photo = r.photo?.let(chatStore::photoFile)?.takeIf { it.exists() }
            val withJpeg = index >= records.size - RESTORE_PHOTOS
            val jpeg = if (withJpeg && photo != null) withContext(Dispatchers.IO) { photo.readBytes() } else null
            val exchange = conversation.add(r.asked, jpeg, r.answer)
            if (photo != null) {
                withContext(Dispatchers.IO) { decodePreview(photo) }?.let { frames[exchange.id] = it }
            }
            r.source?.let { name -> ImageSource.entries.firstOrNull { it.name == name } }?.let { frameSources[exchange.id] = it }
            r.model?.let { labels[exchange.id] = it }
            parts[exchange.id] = r.summary to r.details
            r.shown?.let { shownQuestions[exchange.id] = it }
            if (r.interrupted) interrupted += exchange.id
            if (r.silent) silentIds += exchange.id
            extras[exchange.id] = Extras(
                sources = r.sources,
                notes = r.notes.mapNotNull { ref ->
                    notesById[ref.id]?.let { NoteCard(it, noteStore.photo(it, NOTE_PREVIEW_SIDE), ref.saved) }
                },
                deletedNotes = r.deletedNotes,
                translation = r.translation,
                ratesNote = r.ratesDate?.let { runCatching { RatesNote(LocalDate.parse(it), r.ratesOffline) }.getOrNull() },
                links = r.links.map { FoundLink(LinkCheck.analyze(it.raw), it.fromQr) },
            )
        }
        publishChat()
    }

    /** Снимок обмена для чата — уменьшенный (как превью заметок), чтобы длинная история не занимала память. */
    private fun decodePreview(file: java.io.File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= NOTE_PREVIEW_SIDE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Обмен — в файл чата (по очереди; первый создаёт чат). Снимок — в медиа чатов. */
    private fun save(exchange: StoredExchange, jpeg: ByteArray?) {
        val target = chat
        val fromGlasses = turnFromGlasses
        val unread = !viewing
        viewModelScope.launch {
            withContext(NonCancellable) {
                saveLock.withLock {
                    val record = exchange.copy(photo = jpeg?.let { chatStore.savePhoto(it) })
                    val id = target.id
                    if (id == null) {
                        target.id = chatStore.create(record, fromGlasses, unread)
                        if (target === chat) _currentChatId.value = target.id
                        Log.d(TAG, "Chats: new chat ${target.id} fromGlasses=$fromGlasses")
                    } else {
                        chatStore.append(id, record, unread)
                    }
                }
            }
        }
    }

    private fun run(
        withFrame: Boolean,
        presetQuestion: String? = null,
        mode: AssistantMode = AssistantMode.CHAT,
        silent: Boolean = false,
        origin: Origin = Origin.ASSISTANT,
        byGesture: Boolean = false,
    ) {
        if (_state.value.phase.interruptible) Log.d(TAG, "Assistant: answer interrupted, listening for a new question")
        linkOffer?.cancel()
        job?.cancel()
        audio.stop()
        // Без очков и гарнитуры ответ — только текстом (решение В14): «Озвучить» — по кнопке.
        val quiet = silent || !hasVoiceOutput()
        job = viewModelScope.launch {
            linkScan?.cancel()
            linkScan = null
            _state.update { it.copy(confirmLink = null) }
            // После 2 часов тишины — новый чат; прежний остаётся в истории.
            if (!conversation.isEmpty && ChatRules.expired(lastActivity, System.currentTimeMillis())) {
                Log.d(TAG, "Chats: new chat after a long pause")
                startNewChat()
            }
            // Чат «с очков»: жест, «Hey Meta» или вопрос микрофоном очков.
            turnFromGlasses = byGesture || nextFromGlasses || (presetQuestion == null && !silent && router.micHeadset()?.glasses == true)
            nextFromGlasses = false
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
                    if (byGesture) Beeps.listening()
                    haptics.listenStart()
                    try {
                        if (byGesture) {
                            listenAfterGesture(language) { endOfSpeechAt = it } ?: return@launch cancelGestureQuestion()
                        } else {
                            speech.listen(
                                language.speechTag,
                                onPartial = { partial -> _state.update { it.copy(pendingQuestion = partial) } },
                                onLevel = { _micLevel.value = it },
                            ).also { endOfSpeechAt = it.endOfSpeechAt }.text
                        }
                    } finally {
                        _micLevel.value = 0f
                        haptics.listenEnd()
                    }
                }
                _state.update { it.copy(pendingQuestion = question) }

                if (Conversation.isResetCommand(question, L.array(R.array.reset_commands))) {
                    startNewChat()
                    if (!quiet) {
                        setPhase(Phase.SPEAKING)
                        audio.speak(L.s(R.string.say_new_conversation), language.locale, AudioChannel.ME)
                    }
                    setPhase(Phase.IDLE)
                    return@launch
                }

                // Команды навигации («веди в отель», «как вернуться», «запомни, это мой отель»…) — до модели.
                // Только пока умение «Навигация» включено; выключено — фраза уходит модели как обычный вопрос.
                val navCommand = navCommandHandler?.takeIf { skills.isEnabled(Skill.NAVIGATION.name) }
                    ?.let { NavCommands.parse(question, navVocabulary?.invoke() ?: return@let null) }
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

                val links = answer(question, jpeg, language, TurnMetrics(endOfSpeechAt), turnMode, quiet, origin)
                if (links.isNotEmpty() && !quiet) offerLinks(links, language)
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
            } catch (e: SpeechException) {
                // «Не расслышал» и прочие ошибки распознавания — подсказка на экране, без трассировки в логе.
                Log.d(TAG, "Speech: ${e.message} noSpeech=${e.noSpeech}")
                _state.update { it.copy(phase = Phase.IDLE, error = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "Request failed", e)
                val message = e.message ?: L.s(R.string.something_went_wrong)
                _state.update { it.copy(phase = Phase.IDLE, error = message) }
            }
        }
    }

    /**
     * Вопрос после жеста. null — за [GESTURE_SILENCE_MS] не прозвучало ни слова (или распознаватель ничего
     * не услышал): ложное срабатывание, отменяем молча.
     */
    private suspend fun listenAfterGesture(language: AppLanguage, onEnd: (Long) -> Unit): String? = coroutineScope {
        val spoke = CompletableDeferred<Unit>()
        val listening = async {
            speech.listen(
                language.speechTag,
                onPartial = { partial ->
                    if (partial.isNotBlank()) spoke.complete(Unit)
                    _state.update { it.copy(pendingQuestion = partial) }
                },
                onLevel = { _micLevel.value = it },
            )
        }
        val watchdog = launch {
            delay(GESTURE_SILENCE_MS)
            if (!spoke.isCompleted) listening.cancel()
        }
        try {
            listening.await().also { onEnd(it.endOfSpeechAt) }.text
        } catch (e: SpeechException) {
            if (e.noSpeech) null else throw e
        } catch (e: CancellationException) {
            // Отменил сторожевой таймер (сам ход не отменён) — тишина; иначе отмена хода, пробрасываем.
            if (isActive && !spoke.isCompleted) null else throw e
        } finally {
            watchdog.cancel()
        }
    }

    /** Ложное срабатывание жеста: «отбой» в очки, без ошибки на экране и без запроса. */
    private suspend fun cancelGestureQuestion() {
        Log.d(TAG, "Assistant: gesture question cancelled, no speech in ${GESTURE_SILENCE_MS} ms")
        _state.update { it.copy(phase = Phase.IDLE, pendingQuestion = "") }
        // Пока SCO не разорван, сигнал ушёл бы в очки в качестве звонка.
        router.awaitRouteSettled()
        Beeps.cancelled()
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
        // Снимок из поля ввода: уже в ленте, второй раз не добавляем.
        _attachment.value?.let { attached ->
            _attachment.value = null
            _state.update { it.copy(pendingFrame = attached.preview, pendingFrameSource = attached.source) }
            linkScan = viewModelScope.async { linkScanner.scan(attached.jpeg) }
            return attached.jpeg
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
        // Календарь — только если я сам о нём попросил (слово из calendar_triggers в моей фразе), как и удаление заметок.
        val plan = ToolPlan.of(mode, question, L.array(R.array.delete_note_triggers)).let {
            if (mode == AssistantMode.CHAT && ToolPlan.asksToDelete(question, L.array(R.array.calendar_triggers))) it.copy(calendar = true) else it
        }
        val myCurrency = currency.currency.value
        val where = whereabouts(mode)
        Log.d(TAG, "Assistant: mode=$mode web=${plan.webSearch} notes=${plan.notes} delete=${plan.deleteNotes} country=${where.country}")
        // Какой промпт профиля ушёл в запрос (сам текст — не в лог): свой или по умолчанию.
        skills.active.value?.let { profile ->
            Log.d(TAG, "Assistant: profile=${profile.name} prompt=${if (skills.hasCustomPrompt(profile)) "custom" else "default"} chars=${skills.prompt(profile).length} applied=${mode == AssistantMode.CHAT}")
        }
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
            gestures.answering {
                Log.d(TAG, "Assistant: interrupted by headset button or glasses tap")
                // С «Жестом на дужке» перебивание — как вопрос жестом: сигнал и тихая отмена, если промолчали.
                if (gestureSettings.chosen != null) gestureAsk() else ask()
            }
        }
        try {
            coroutineScope {
                // Голосовое «стоп»: микрофон гарнитуры включается параллельно с запросом к модели,
                // чтобы первая фраза уже звучала по тому же маршруту (без переключения посреди ответа).
                val micHeld = if (!silent && profile.voiceStop.value && router.micHeadset()?.mic != null) async { router.holdMic() } else null
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
                    onCalendarEvent = { _calendarEvents.tryEmit(it) },
                )

                val result = assistant.stream(
                    mode,
                    plan,
                    tools?.toolSet(),
                    conversation.turnsFor(asked, jpeg),
                    language,
                    myCurrency,
                    where,
                    // Стиль — только к ответам голосом (В5); профиль — ко всем ответам чата.
                    styleSettings.current.value.takeUnless { silent },
                    skills.active.value?.let(skills::prompt),
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
                gestures.answerDone()
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
        save(
            StoredExchange(
                asked = asked,
                shown = question.takeIf { it != asked },
                summary = summary,
                details = details,
                model = tag,
                source = _state.value.pendingFrameSource?.name ?: frameSources[exchange.id]?.name,
                interrupted = wasInterrupted,
                silent = silent,
                translation = extra.translation,
                sources = extra.sources,
                notes = extra.notes.map { NoteRef(it.note.id, it.saved) },
                deletedNotes = extra.deletedNotes,
                ratesDate = extra.ratesNote?.date?.toString(),
                ratesOffline = extra.ratesNote?.offline == true,
                links = extra.links.map { LinkRef(it.info.raw, it.fromQr) },
            ),
            jpeg,
        )
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

    /**
     * Ошибка на экран и голосом: с очками на экран не смотрят («нет интернета», «очки не подключены» и т. п.).
     * Без очков и гарнитуры — только на экране.
     */
    private suspend fun failAloud(message: String) {
        if (!hasVoiceOutput()) {
            _state.update { it.copy(phase = Phase.IDLE, error = message) }
            return
        }
        _state.update { it.copy(phase = Phase.SPEAKING, error = message) }
        runCatching { audio.speak(message, LanguageSettings.current.value.locale, AudioChannel.ME) }
        setPhase(Phase.IDLE)
    }

    /** Есть куда говорить: очки или Bluetooth-гарнитура со звуком. Иначе ответы — только текстом (В14). */
    private fun hasVoiceOutput(): Boolean = router.headset.value?.output != null

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
        inserts.clear()
        lastActivity = 0L
        _state.update { UiState(phase = it.phase) }
    }

    /** Перезапускает таймер автосброса после 5 минут тишины. */
    private fun touch() {
        lastActivity = System.currentTimeMillis()
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
        }.toMutableList()
        // Вставки — на свои места, начиная с конца, чтобы индексы впереди не сдвигались.
        inserts.withIndex().reversed().forEach { (i, entry) ->
            val (position, insert) = entry
            val item = ChatItem(
                -(i + 1L), "", "", null, remembered = true, model = null,
                divider = (insert as? ChatInsert.Divider)?.text,
                translated = (insert as? ChatInsert.Translated)?.line,
            )
            chat.add(position.coerceIn(0, chat.size), item)
        }
        _state.update { it.copy(chat = chat) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    override fun onCleared() {
        audio.stop()
        gestures.answerDone()
    }

    init {
        // Приложение открывается в последнем чате, если с него не прошло 2 часа; иначе — новый чат.
        chatStore.chats.value.firstOrNull()
            ?.takeUnless { ChatRules.expired(it.updated, System.currentTimeMillis()) }
            ?.let { openChat(it.id) }
    }

    private companion object {
        /** Сколько последних обменов при открытии чата снова получают свой снимок картинкой (модель видит последний). */
        const val RESTORE_PHOTOS = 10
        const val VOICE_CONFIRM_MS = 10_000L
        const val REMEMBER_LISTEN_MS = 12_000L
        const val ECHO_GUARD_MS = 1_000L
        const val GESTURE_SILENCE_MS = 5_000L
        const val NOTE_PREVIEW_SIDE = 640
        const val MAX_FOUND_CARDS = 3
    }
}
