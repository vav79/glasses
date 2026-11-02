package ru.vasev.glasses

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.CompletableDeferred
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.DebugFaults
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ProviderId
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.nav.AddressSearchViewModel
import ru.vasev.glasses.nav.NavHooks
import ru.vasev.glasses.nav.PlacesViewModel
import ru.vasev.glasses.nav.TargetViewModel
import ru.vasev.glasses.nav.TaxiViewModel
import ru.vasev.glasses.speech.GesturesService
import ru.vasev.glasses.speech.Tap
import ru.vasev.glasses.ui.NavViewModels
import ru.vasev.glasses.translate.PartnerLanguage
import ru.vasev.glasses.translate.TranslationViewModel
import ru.vasev.glasses.translate.Who
import androidx.compose.ui.graphics.toArgb
import ru.vasev.glasses.ui.theme.sputnikColorScheme
import ru.vasev.glasses.ui.theme.ThemeMode
import ru.vasev.glasses.ui.theme.isDark
import ru.vasev.glasses.ui.shell.AppShell
import ru.vasev.glasses.ui.shell.ChatEntry
import ru.vasev.glasses.ui.shell.MenuItem
import ru.vasev.glasses.ui.shell.ModelStatus
import ru.vasev.glasses.ui.shell.ShellHeader
import ru.vasev.glasses.ui.shell.ProfileOption
import ru.vasev.glasses.profile.AssistantProfile
import ru.vasev.glasses.profile.Skill
import ru.vasev.glasses.ui.shell.chatTitle
import ru.vasev.glasses.ui.shell.AllChatsScreen
import ru.vasev.glasses.ui.shell.DeleteChatDialog
import ru.vasev.glasses.chats.ChatRules
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.vasev.glasses.ui.SkillsScreen
import ru.vasev.glasses.ui.CalendarPlaceholder
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor
import ru.vasev.glasses.profile.AnswerStyle
import ru.vasev.glasses.profile.Formality
import ru.vasev.glasses.profile.SpeakerGender
import ru.vasev.glasses.profile.SpeechPace
import ru.vasev.glasses.ui.AppTab
import ru.vasev.glasses.ui.DevScreen
import ru.vasev.glasses.ui.glass.GlassRoot
import ru.vasev.glasses.ui.AgreementScreen
import ru.vasev.glasses.ui.ModelSetupScreen
import ru.vasev.glasses.profile.AgreementSettings
import ru.vasev.glasses.ui.MainScreen
import ru.vasev.glasses.ui.CameraScreen
import ru.vasev.glasses.ui.AssistantOverlays
import ru.vasev.glasses.ui.MainViewModel
import ru.vasev.glasses.ui.NavigationScreen
import ru.vasev.glasses.ui.openMapsLink
import ru.vasev.glasses.ui.SettingsScreen
import ru.vasev.glasses.ui.SettingsViewModel
import ru.vasev.glasses.ui.TranslationScreen
import ru.vasev.glasses.ui.theme.GlassesTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val translation: TranslationViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val addressSearch: AddressSearchViewModel by viewModels()
    private val navTarget: TargetViewModel by viewModels()
    private val taxi: TaxiViewModel by viewModels()
    private val places: PlacesViewModel by viewModels()
    private val navSession get() = (application as GlassesApp).navSession

    private var tab by mutableStateOf(AppTab.ASSISTANT)

    /** «Заметки» открыты поверх чата (меню ⋮ шапки). */
    private var notesOpen by mutableStateOf(false)

    /** Чат, удаление которого ждёт подтверждения. */
    private var chatToDelete by mutableStateOf<ChatEntry?>(null)

    /** Настройки открыты поверх вкладки (шестерёнка). */
    private var settingsOpen by mutableStateOf(false)

    /** Внутри настроек открыт раздел «Для разработчика» (журнал запросов). */
    private var developerOpen by mutableStateOf(false)

    /** Есть ли доступ к камере телефона; перепроверяется при каждом возврате в приложение (могли отозвать в настройках). */
    private var cameraGranted by mutableStateOf(false)

    private var pendingMicAction: (() -> Unit)? = null
    // Ждущие ответа системы запросы — в [PendingRequests]: переживают пересоздание Activity (поворот).
    private val requests: PendingRequests by viewModels()
    private var pendingCameraPermission: CompletableDeferred<Boolean>?
        get() = requests.glassesCamera
        set(value) { requests.glassesCamera = value }

    private val runtimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val action = pendingMicAction
            pendingMicAction = null
            if (granted[Manifest.permission.RECORD_AUDIO] == true || hasMic()) action?.invoke()
        }

    private val cameraPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            pendingCameraPermission?.complete(result.getOrNull() is PermissionStatus.Granted)
            pendingCameraPermission = null
        }

    /** Разрешение на камеру телефона — запрашивается при выборе источника «Камера телефона». */
    private val phoneCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            if (granted) {
                viewModel.setImageSource(ImageSource.PHONE_CAMERA)
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    /** Камера телефона для снимка по кнопке (фото входа, фото адреса в «Навигации»). */
    private var pendingPhoneCamera: CompletableDeferred<Boolean>?
        get() = requests.phoneCamera
        set(value) { requests.phoneCamera = value }
    private val phoneCameraForShot =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            pendingPhoneCamera?.complete(granted)
            pendingPhoneCamera = null
        }

    /** Уведомление «Компаса» (Android 13+): спрашиваем при первом запуске компаса; отказ компасу не мешает. */
    private var pendingNotifications: CompletableDeferred<Boolean>?
        get() = requests.notifications
        set(value) { requests.notifications = value }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingNotifications?.complete(granted)
            pendingNotifications = null
        }

    /** Геопозиция для заметок «Запомни» — спрашивается при первой заметке. */
    private var pendingLocationPermission: CompletableDeferred<Boolean>?
        get() = requests.location
        set(value) { requests.location = value }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            pendingLocationPermission?.complete(granted.values.any { it })
            pendingLocationPermission = null
        }

    /** Системный выбор фото: приложение получает только выбранный снимок, без доступа ко всей галерее. */
    private var pendingPhoto: CompletableDeferred<Uri?>?
        get() = requests.photo
        set(value) { requests.photo = value }
    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            pendingPhoto?.complete(uri)
            pendingPhoto = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Заставка при запуске (SplashScreen API) — до super.onCreate.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Activity пересоздаётся при смене языка приложения или системы; ViewModel и история разговора остаются.
        LanguageSettings.refresh()
        val profile = (application as GlassesApp).profile
        // Приложение открывается сразу в чат; после пересоздания — тот экран, что был открыт.
        tab = savedInstanceState?.getString(STATE_TAB)?.let { name -> AppTab.entries.firstOrNull { it.name == name } }
            ?: AppTab.ASSISTANT
        settingsOpen = savedInstanceState?.getBoolean(STATE_SETTINGS) ?: false
        developerOpen = settingsOpen && savedInstanceState?.getBoolean(STATE_DEVELOPER) == true
        // До первого кадра Compose система показывает фон окна: пусть он будет цвета темы приложения.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySystemBars(
            when ((application as GlassesApp).appearance.theme.value) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> night
            },
        )
        viewModel.cameraPermissionRequester = ::requestGlassesCamera
        viewModel.galleryPicker = ::pickPhoto
        viewModel.cameraOwner = this
        viewModel.locationPermissionRequester = ::requestLocation
        navSession.attach(
            NavHooks(
                owner = this,
                requestLocation = ::requestLocation,
                requestPhoneCamera = ::requestPhoneCamera,
                requestGlassesCamera = ::requestGlassesCamera,
                requestNotifications = ::requestNotifications,
                pickPhoto = ::pickPhoto,
            ),
        )
        val navVoice = (application as GlassesApp).navVoice
        viewModel.navVocabulary = navVoice::vocabulary
        viewModel.navCommandHandler = navVoice::handle
        // Вкладка «Навигация» — когда ход ассистента закончен, следующим кадром: иначе размытие (Haze) получает
        // обновление уже убранного с экрана элемента и падает.
        viewModel.navCommandDone = { command ->
            if (navVoice.showsTab(command)) window.decorView.post { selectTab(AppTab.NAVIGATION, stopOthers = false) }
        }
        // После пересоздания интент тот же — debug-сценарий и регистрацию повторно не запускаем.
        if (savedInstanceState == null) {
            handleRegistrationIntent(intent)
            handleVoiceIntent(intent)
            handleDebugIntent(intent)
        }
        requestRuntimePermissions()
        // «Hey Meta, start …»: запуск ждёт, пока экран на виду, — тогда слушаем вопрос и отвечаем Meta AI.
        val voiceLaunch = (application as GlassesApp).voiceLaunch
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                voiceLaunch.pending.filterNotNull().collect { handleVoiceLaunch() }
            }
        }
        // «Жест на дужке»: вопрос, пока Activity жива — и в фоне, и с погасшим экраном (держит GesturesService).
        // Сервис запускается только отсюда, пока приложение на экране: иначе Android не даст микрофон из фона.
        val app = application as GlassesApp
        app.gestures.onAsk = ::handleGestureAsk
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.gestureSettings.enabled.collect { GesturesService.sync(this@MainActivity, it && hasMic()) }
            }
        }

        setContent {
            val appearance = (application as GlassesApp).appearance
            val themeMode by appearance.theme.collectAsStateWithLifecycle()
            val fontScale by appearance.fontScale.collectAsStateWithLifecycle()
            val dark = themeMode.isDark()
            // Значки строки состояния — под тему приложения, а не системы (тёмная тема при светлой системе).
            LaunchedEffect(dark) { applySystemBars(dark) }
            GlassesTheme(dark = dark, fontScalePercent = fontScale) {
                val app = application as GlassesApp
                val agreed by app.agreement.acceptedVersion.collectAsStateWithLifecycle()
                val onboarded by profile.onboarded.collectAsStateWithLifecycle()
                // Самый первый экран — соглашение (и при новой редакции): без него приложение дальше не запускается.
                if (!AgreementSettings.isCurrent(agreed)) {
                    GlassRoot { AgreementScreen(readOnly = false, onAccept = app.agreement::accept, onExit = ::finishAffinity) }
                    return@GlassesTheme
                }
                // Затем — выбор модели и ключ (один раз); дальше приложение открывается сразу в чат.
                if (!onboarded) {
                    val savedKeys by app.apiKeys.saved.collectAsStateWithLifecycle()
                    GlassRoot {
                        ModelSetupScreen(
                            savedKeys = savedKeys,
                            onSaveKey = ::saveFirstKey,
                            onDone = {
                                profile.completeOnboarding(profile.role.value)
                                selectTab(AppTab.ASSISTANT)
                            },
                        )
                    }
                    return@GlassesTheme
                }
                Shell()
            }
        }
    }

    /**
     * Ключ из первого запуска — зашифровать и сохранить; null — сохранён, иначе текст ошибки (формат ключа).
     * Есть только ключ DeepSeek — ассистент и перевод сразу на DeepSeek Flash (иначе модель по умолчанию — Claude без ключа).
     */
    private fun saveFirstKey(provider: ProviderId, key: String): String? {
        val app = application as GlassesApp
        return try {
            app.apiKeys.save(provider, key)
            if (provider == ProviderId.DEEPSEEK && !app.apiKeys.has(ProviderId.ANTHROPIC)) {
                Role.entries.forEach { app.aiSettings.setModel(it, ModelCatalog.DEEPSEEK_FLASH) }
            }
            null
        } catch (e: Exception) {
            e.message ?: getString(R.string.something_went_wrong)
        }
    }

    /** Каркас «Классики»: боковое меню, шапка и текущий экран. */
    @Composable
    private fun Shell() {
        val app = application as GlassesApp
        val chat by viewModel.state.collectAsStateWithLifecycle()
        val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
        val battery by viewModel.glassesBattery.collectAsStateWithLifecycle()
        val model by viewModel.model.collectAsStateWithLifecycle()
        val hasKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
        val chatTitle = chat.chat.firstOrNull()?.question?.let(::chatTitle) ?: stringResource(R.string.menu_new_chat)
        val notesLabel = stringResource(R.string.notes_open)
        val deleteLabel = stringResource(R.string.chat_delete)
        val chatMetas by viewModel.chats.collectAsStateWithLifecycle()
        val activeProfile by app.skills.active.collectAsStateWithLifecycle()
        val disabledSkills by app.skills.disabled.collectAsStateWithLifecycle()
        val currentChatId by viewModel.currentChatId.collectAsStateWithLifecycle()
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        // Ответ, пришедший, пока чат не на экране (приложение свёрнуто, другой экран), — непрочитанный.
        val viewing = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && tab == AppTab.ASSISTANT && !settingsOpen && !notesOpen
        LaunchedEffect(viewing) { viewModel.setViewing(viewing) }
        fun entries(query: String) = ChatRules.search(chatMetas, query).map {
            ChatEntry(it.id, it.title, selected = it.id == currentChatId && tab == AppTab.ASSISTANT, fromGlasses = it.fromGlasses, unread = it.unread)
        }
        val openChat: (ChatEntry) -> Unit = { entry ->
            viewModel.openChat(entry.id)
            selectTab(AppTab.ASSISTANT)
        }
        chatToDelete?.let { entry ->
            DeleteChatDialog(entry.title, onConfirm = {
                viewModel.deleteChat(entry.id)
                chatToDelete = null
            }, onDismiss = { chatToDelete = null })
        }
        val header = when {
            settingsOpen -> ShellHeader.Title(stringResource(if (developerOpen) R.string.dev_journal_title else R.string.tab_settings))
            tab == AppTab.ASSISTANT -> ShellHeader.Chat(
                profileTitle = activeProfile?.let { stringResource(it.titleRes) } ?: stringResource(R.string.profile_none),
                profileIcon = activeProfile?.icon,
                profileOptions = buildList {
                    add(ProfileOption(stringResource(R.string.profile_none), null, activeProfile == null) { viewModel.setProfile(null) })
                    AssistantProfile.entries.filter { it.name !in disabledSkills }.forEach { profile ->
                        add(ProfileOption(stringResource(profile.titleRes), profile.icon, profile == activeProfile) { viewModel.setProfile(profile) })
                    }
                },
                translateOn = false,
                glassesReady = glassesStatus.ready,
                glassesBattery = battery,
                // Кнопка перевода до этапа 4 открывает старый экран «Перевод».
                onTranslate = { selectTab(AppTab.TRANSLATE) },
                onGlasses = { selectTab(AppTab.CAMERA) },
                chatMenu = buildList {
                    add(notesLabel to { notesOpen = true })
                    // Удалить можно только сохранённый чат (новый, без обменов, ещё не в истории).
                    currentChatId?.let { id -> add(deleteLabel to { chatToDelete = ChatEntry(id, chatTitle, selected = true) }) }
                },
            )
            else -> ShellHeader.Title(stringResource(if (tab == AppTab.TRANSLATE) R.string.skill_translator else tab.titleRes))
        }
        AppShell(
            header = header,
            chats = ::entries,
            onShowAllChats = { selectTab(AppTab.CHATS) },
            onChatLongPress = { chatToDelete = it },
            modelName = when (model.provider) {
                ProviderId.ANTHROPIC -> "Claude"
                ProviderId.DEEPSEEK -> "DeepSeek"
                ProviderId.OPENAI -> "ChatGPT"
            },
            modelStatus = when {
                !hasKey -> ModelStatus.NO_KEY
                !app.llm.isOnline() -> ModelStatus.UNAVAILABLE
                else -> ModelStatus.AVAILABLE
            },
            onMenuItem = { item ->
                when (item) {
                    MenuItem.NEW_CHAT -> {
                        viewModel.newConversation()
                        selectTab(AppTab.ASSISTANT)
                    }
                    // «Медиа» и «Очки» до этапов 5–6 — старая «Камера»: лента снимков, подключение очков.
                    MenuItem.MEDIA, MenuItem.GLASSES -> selectTab(AppTab.CAMERA)
                    MenuItem.SKILLS -> selectTab(AppTab.SKILLS)
                    MenuItem.CALENDAR -> selectTab(AppTab.CALENDAR)
                }
            },
            onChat = openChat,
            onNotifications = { Toast.makeText(this, R.string.notifications_soon, Toast.LENGTH_SHORT).show() },
            onSettings = {
                settingsOpen = true
                developerOpen = false
            },
            onModel = {
                settingsOpen = true
                developerOpen = false
            },
            onBack = when {
                developerOpen -> ({ developerOpen = false })
                settingsOpen -> ({ settingsOpen = false })
                tab != AppTab.ASSISTANT -> ({ selectTab(AppTab.ASSISTANT) })
                else -> null
            },
        ) {
            if (settingsOpen) {
                if (developerOpen) {
                    val journal = app.journal
                    DevScreen(journal.entries, onExport = ::shareJournal, onClear = journal::clear, onClose = { developerOpen = false })
                } else {
                    SettingsScreen(settings, onOpenDeveloper = { developerOpen = true })
                }
                return@AppShell
            }
            when (tab) {
                AppTab.ASSISTANT -> MainScreen(
                    viewModel = viewModel,
                    onAsk = { withMic(viewModel::ask) },
                    onOpenSettings = { settingsOpen = true },
                    requestPhoneCamera = ::requestPhoneCamera,
                    notesOpen = notesOpen,
                    onCloseNotes = { notesOpen = false },
                )
                AppTab.CAMERA -> {
                    CameraScreen(
                        viewModel = viewModel,
                        cameraGranted = cameraGranted,
                        onSelectSource = ::selectImageSource,
                        onConnectGlasses = { app.glasses.connect(this) },
                        onDescribe = viewModel::cameraDescribe,
                        onAsk = { withMic(viewModel::cameraAsk) },
                        onTranslate = viewModel::cameraTranslate,
                        onRemember = { withMic(viewModel::cameraRemember) },
                        onFollowUp = { withMic(viewModel::cameraFollowUp) },
                    )
                    AssistantOverlays(viewModel)
                }
                AppTab.TRANSLATE -> TranslationScreen(
                    viewModel = translation,
                    onPartnerSpeaks = { withMic(translation::partnerSpeaks) },
                    onMeReplies = { withMic(translation::meReplies) },
                    onInstallVoice = ::openVoiceInstaller,
                    onOpenSettings = { settingsOpen = true },
                )
                AppTab.NAVIGATION -> NavigationScreen(
                    vms = NavViewModels(navSession, addressSearch, navTarget, taxi, places),
                    onMic = { withMic(addressSearch::listenQuery) },
                    onOpenLink = { openMapsLink(this, it) },
                    onLaunchApp = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) },
                )
                AppTab.SKILLS -> SkillsScreen(app.skills, onOpenSkill = { skill ->
                    // Настройки умений — этап 4б; пока «Переводчик» и «Навигация» открывают свои прежние экраны.
                    selectTab(if (skill == Skill.TRANSLATOR) AppTab.TRANSLATE else AppTab.NAVIGATION, stopOthers = false)
                })
                AppTab.CALENDAR -> CalendarPlaceholder()
                AppTab.CHATS -> AllChatsScreen(chats = ::entries, onChat = openChat, onLongPress = { chatToDelete = it })
            }
        }
    }

    /** Строка состояния и навигации: прозрачные, значки — светлые в тёмной теме и тёмные в светлой. */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(sputnikColorScheme(dark).surface.toArgb()))
    }
    /** Камера телефона — только после разрешения; остальные источники разрешений Android не требуют. */
    private fun selectImageSource(source: ImageSource) {
        val needsCamera = source == ImageSource.PHONE_CAMERA &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        if (needsCamera) phoneCameraPermission.launch(Manifest.permission.CAMERA) else viewModel.setImageSource(source)
    }

    private suspend fun pickPhoto(): Uri? {
        val deferred = CompletableDeferred<Uri?>()
        pendingPhoto?.complete(null)
        pendingPhoto = deferred
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        return deferred.await()
    }

    /** Вкладки независимы: при переключении всё, что звучит или слушает на другой вкладке, останавливается. */
    private fun selectTab(newTab: AppTab, stopOthers: Boolean = true) {
        settingsOpen = false
        developerOpen = false
        if (newTab == tab) return
        if (!stopOthers) {
            tab = newTab
            return
        }
        // «Ассистент» и «Камера» — один разговор: между ними ответ не прерывается.
        val sameConversation = tab in CONVERSATION_TABS && newTab in CONVERSATION_TABS
        if (!sameConversation) {
            when (tab) {
                AppTab.ASSISTANT, AppTab.CAMERA -> viewModel.stop()
                AppTab.TRANSLATE -> translation.stop()
                AppTab.NAVIGATION -> navSession.cancel()
                // Списки и заглушки — ни звука, ни микрофона.
                AppTab.SKILLS, AppTab.CALENDAR, AppTab.CHATS -> Unit
            }
        }
        tab = newTab
    }

    /** Журнал запросов — копией в JSONL через системное «Поделиться» (FileProvider, только папка экспорта). */
    private fun shareJournal() {
        val file = (application as GlassesApp).journal.exportCopy()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.dev_journal_title)))
    }

    private fun openVoiceInstaller() {
        // Открываем установку голосов именно того движка, которым говорит приложение, — без диалога выбора.
        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            .setPackage((application as GlassesApp).speaker.enginePackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val settings = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(install) }
            .recoverCatching { startActivity(settings) }
            .onFailure { Log.w(TAG, "Cannot open TTS settings", it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRegistrationIntent(intent)
        handleVoiceIntent(intent)
        handleDebugIntent(intent)
    }

    /** Запущены голосом через Meta AI: сразу «Ассистент». Слушать начнём по событию из потока ([handleVoiceLaunch]). */
    private fun handleVoiceIntent(intent: Intent) {
        if (!isVoiceInvocationsIntent(intent)) return
        Log.d(TAG, "VoiceInvocation: launched by Meta AI, tab=$tab")
        selectTab(AppTab.ASSISTANT)
    }

    /**
     * Голосовой запуск — всегда «Ассистент»: идущий перевод останавливается (selectTab), открытые настройки закрываются.
     * Компас работает дальше — он живёт на всё приложение (CompassController + foreground-сервис), а не на вкладке.
     */

    private fun handleVoiceLaunch() {
        val app = application as GlassesApp
        val launch = app.voiceLaunch.take() ?: return
        when {
            !app.profile.onboarded.value -> app.voiceLaunch.respond(launch, ok = false, reason = "first-run setup is not finished")
            !hasMic() -> {
                app.voiceLaunch.respond(launch, ok = false, reason = "no microphone permission")
                requestRuntimePermissions()
            }
            else -> {
                Log.d(TAG, "VoiceInvocation: claimed on tab=$tab settings=$settingsOpen")
                selectTab(AppTab.ASSISTANT)
                val ok = viewModel.voiceLaunch()
                app.voiceLaunch.respond(launch, ok, reason = if (ok) "" else "no API key")
            }
        }
    }

    /** Жест на дужке в покое: как голосовой запуск — «Ассистент» и сразу слушать вопрос. */
    private fun handleGestureAsk() {
        val app = application as GlassesApp
        when {
            !app.profile.onboarded.value -> Log.d(TAG, "gesture: ignored, first-run setup is not finished")
            !hasMic() -> Log.d(TAG, "gesture: ignored, no microphone permission")
            else -> {
                Log.d(TAG, "gesture: ask on tab=$tab settings=$settingsOpen")
                selectTab(AppTab.ASSISTANT)
                viewModel.gestureAsk()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
        outState.putBoolean(STATE_SETTINGS, settingsOpen)
        outState.putBoolean(STATE_DEVELOPER, developerOpen)
    }

    /** Язык приложения сменился без пересоздания (configChanges): строки вне экрана и язык общения — на новый. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LanguageSettings.refresh()
        settings.syncLanguage()
    }

    override fun onResume() {
        super.onResume()
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        viewModel.cameraPermissionRequester = null
        viewModel.galleryPicker = null
        viewModel.cameraOwner = null
        viewModel.locationPermissionRequester = null
        navSession.detach(this)
        // Открытая цель и экраны «Навигации» живут до закрытия приложения, как и состояние остальных вкладок.
        if (isFinishing) navSession.closeScreens()
        viewModel.navCommandHandler = null
        viewModel.navCommandDone = null
        viewModel.navVocabulary = null
        // Без экрана жест скажет «откройте приложение» (GlassesApp); при повороте новая Activity поставит своё.
        (application as GlassesApp).gestures.onAsk = null
        // Поворот: открытый системный диалог или галерея ответит новой Activity — запросы не отменяем.
        if (!isChangingConfigurations) requests.cancelAll()
        super.onDestroy()
    }

    /**
     * Debug-запуск сценариев без голоса:
     *  - ассистент: --es question "Текст" [--ez with_frame true]
 *  - «Переведи, что вижу»: --ez translate_view true
 *  - снимок из файла files/<имя>: --es frame_file qr.png (вместе с question + with_frame или translate_view)
     *  - перевод:   --es translate_partner "Merhaba" | --es translate_me "Привет" [--es language TURKISH]
     *  - модели:    --es model_assistant deepseek-v4-pro | --es model_translation deepseek-flash
     *  - жест:      --es gesture double|single|off
     *  - стиль:     --es style_answer guide --es style_formality casual --es style_gender male|female|unset --es style_pace slower
     *  - сбой:      --es fail_claude server|network|forbidden|ratelimit (следующий запрос к Claude)
     *  - снимки:    --es image_source glasses|phone|simulator
     *  - вкладка:    --es tab assistant|camera|translate|navigation|settings|development (журнал в настройках)
     *  - навигация: --es nav_to "Wat Arun" (поиск цели, как в поле «Куда?»)
     *  - компас за столом: --es fake_location "55.7539,37.6208" (подменить место, каждый интент — новая точка)
     *  - первый запуск: --ez reset_onboarding true (выбор модели) / --ez reset_agreement true (соглашение); данные не стираются
     * Порядок внутри одного интента: источник, модели и сбой применяются до вопроса.
     */
    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val app = application as GlassesApp
        if (intent.getBooleanExtra("reset_onboarding", false)) {
            intent.removeExtra("reset_onboarding")
            // Только флаг первого запуска: роль, ключи и история остаются.
            app.profile.resetOnboarding()
        }
        if (intent.getBooleanExtra("reset_agreement", false)) {
            intent.removeExtra("reset_agreement")
            app.agreement.reset()
        }
        intent.getStringExtra("tab")?.let { name ->
            intent.removeExtra("tab")
            // Скриншоты и проверки: вкладка или «settings» — настройки поверх.
            when (name) {
                "settings" -> settingsOpen = true
                "development" -> {
                    settingsOpen = true
                    developerOpen = true
                }
                else -> AppTab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(::selectTab)
            }
        }
        // Компас за столом: подменить место (каждый интент — новая точка) — до вопроса, чтобы команды его видели.
        intent.getStringExtra("fake_location")?.let { value ->
            intent.removeExtra("fake_location")
            val (lat, lon) = value.split(",").map { it.trim().toDouble() }
            app.compass.fakeLocation = ru.vasev.glasses.nav.GeoPoint(lat, lon)
        }
        intent.getStringExtra("image_source")?.let { name ->
            intent.removeExtra("image_source")
            val source = when (name) {
                "glasses" -> ImageSource.GLASSES
                "phone" -> ImageSource.PHONE_CAMERA
                "simulator" -> ImageSource.SIMULATOR
                else -> null
            }
            source?.let(::selectImageSource)
        }
        for ((extra, role) in listOf("model_assistant" to Role.ASSISTANT, "model_translation" to Role.TRANSLATION)) {
            intent.getStringExtra(extra)?.let { id ->
                intent.removeExtra(extra)
                ModelCatalog.byId(id)?.let { app.aiSettings.setModel(role, it) }
            }
        }
        // «Стиль общения» без шторки: --es style_gender male --es style_formality casual (сохраняется, как «Сохранить»).
        val styleExtras = listOf("style_answer", "style_formality", "style_gender", "style_pace")
        if (styleExtras.any(intent::hasExtra)) {
            var style = app.style.current.value
            fun <T : Enum<T>> pick(extra: String, values: Array<T>): T? =
                intent.getStringExtra(extra)?.let { name -> values.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            pick("style_answer", AnswerStyle.entries.toTypedArray())?.let { style = style.copy(answer = it) }
            pick("style_formality", Formality.entries.toTypedArray())?.let { style = style.copy(formality = it) }
            pick("style_gender", SpeakerGender.entries.toTypedArray())?.let { style = style.copy(gender = it) }
            pick("style_pace", SpeechPace.entries.toTypedArray())?.let { style = style.copy(pace = it) }
            styleExtras.forEach(intent::removeExtra)
            app.style.save(style)
            Log.d(TAG, "debug style: $style")
        }
        intent.getStringExtra("fail_claude")?.let { kind ->
            intent.removeExtra("fail_claude")
            DebugFaults.arm(ProviderId.ANTHROPIC, kind)
        }
        // Снимок из файла (в личной папке приложения: adb push в /sdcard/Download, затем run-as cp).
        intent.getStringExtra("frame_file")?.let { name ->
            intent.removeExtra("frame_file")
            viewModel.debugFrameFile = java.io.File(filesDir, name)
        }
        intent.getStringExtra("question")?.let { question ->
            intent.removeExtra("question")
            selectTab(AppTab.ASSISTANT)
            viewModel.debugAsk(question, intent.getBooleanExtra("with_frame", false))
        }
        // Уступка навигатору: через [delay] мс 3 секунды «говорит навигатор» (usage NAVIGATION_GUIDANCE).
        if (intent.hasExtra("fake_navigator_after_ms")) {
            val delayMs = intent.getIntExtra("fake_navigator_after_ms", 0)
            intent.removeExtra("fake_navigator_after_ms")
            lifecycleScope.launch {
                kotlinx.coroutines.delay(delayMs.toLong())
                ru.vasev.glasses.speech.FakeNavigatorVoice.play()
            }
        }
        // Переподключение к Meta AI (например, после смены ID проекта): отключить, затем подключить заново.
        if (intent.getBooleanExtra("dat_unregister", false)) {
            intent.removeExtra("dat_unregister")
            Wearables.startUnregistration(this)
        }
        if (intent.getBooleanExtra("dat_register", false)) {
            intent.removeExtra("dat_register")
            Wearables.startRegistration(this)
        }
        // Оформление без настроек: --es theme dark|light|system --ei font_scale 80|90|100|110|120 (сохраняется).
        intent.getStringExtra("theme")?.let { value ->
            intent.removeExtra("theme")
            ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.let(app.appearance::setTheme)
        }
        if (intent.hasExtra("font_scale")) {
            app.appearance.setFontScale(intent.getIntExtra("font_scale", 100))
            intent.removeExtra("font_scale")
        }
        // «Жест на дужке» без настроек: --es gesture double|single|off (сохраняется).
        intent.getStringExtra("gesture")?.let { value ->
            intent.removeExtra("gesture")
            Tap.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.let(app.gestureSettings::setTap)
            app.gestureSettings.setEnabled(!value.equals("off", ignoreCase = true))
            Log.d(TAG, "debug gesture: enabled=${app.gestureSettings.enabled.value} tap=${app.gestureSettings.tap.value}")
        }
        // «Hey Meta, start …» в симуляторе (источник снимков — «Симулятор очков»).
        if (intent.getBooleanExtra("simulate_hey_meta", false)) {
            intent.removeExtra("simulate_hey_meta")
            Log.d(TAG, "VoiceInvocation: simulated=${app.glasses.simulateVoiceLaunch()}")
        }
        intent.getStringExtra("compass_status")?.let {
            intent.removeExtra("compass_status")
            app.compass.sayStatus()
        }
        intent.getStringExtra("nav_to")?.let { query ->
            intent.removeExtra("nav_to")
            selectTab(AppTab.NAVIGATION)
            addressSearch.setQuery(query)
            addressSearch.search()
        }
        if (intent.getBooleanExtra("translate_view", false)) {
            intent.removeExtra("translate_view")
            selectTab(AppTab.ASSISTANT)
            viewModel.translateWhatISee()
        }
        intent.getStringExtra("language")?.let { name ->
            intent.removeExtra("language")
            PartnerLanguage.entries.firstOrNull { it.name == name }?.let(translation::setLanguage)
        }
        val partner = intent.getStringExtra("translate_partner")
        val me = intent.getStringExtra("translate_me")
        if (partner != null || me != null) {
            intent.removeExtra("translate_partner")
            intent.removeExtra("translate_me")
            selectTab(AppTab.TRANSLATE)
            if (partner != null) translation.debugTurn(Who.PARTNER, partner) else translation.debugTurn(Who.ME, me!!)
        }
    }

    private fun handleRegistrationIntent(intent: Intent) {
        Wearables.handleIntent(intent) { request ->
            request.continueRegistration(this)
                .onFailure { error, _ -> (application as GlassesApp).glasses.registrationRequestFailed(error.description) }
        }
    }

    private suspend fun requestGlassesCamera(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission?.complete(false)
        pendingCameraPermission = deferred
        cameraPermission.launch(Permission.CAMERA)
        return deferred.await()
    }

    private suspend fun requestNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingNotifications?.complete(false)
        pendingNotifications = deferred
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        return deferred.await()
    }

    private suspend fun requestPhoneCamera(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPhoneCamera?.complete(false)
        pendingPhoneCamera = deferred
        phoneCameraForShot.launch(Manifest.permission.CAMERA)
        return deferred.await()
    }

    private suspend fun requestLocation(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingLocationPermission?.complete(false)
        pendingLocationPermission = deferred
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        return deferred.await()
    }

    private fun withMic(action: () -> Unit) {
        if (hasMic()) {
            action()
        } else {
            pendingMicAction = action
            runtimePermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) runtimePermissions.launch(needed.toTypedArray())
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STATE_TAB = "tab"
        const val STATE_SETTINGS = "settings_open"
        const val STATE_DEVELOPER = "developer_open"
        val CONVERSATION_TABS = setOf(AppTab.ASSISTANT, AppTab.CAMERA)
    }
}
