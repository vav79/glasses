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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import ru.vasev.glasses.ui.NavViewModels
import ru.vasev.glasses.translate.PartnerLanguage
import ru.vasev.glasses.translate.TranslationViewModel
import ru.vasev.glasses.translate.Who
import androidx.compose.ui.graphics.toArgb
import ru.vasev.glasses.ui.theme.glassesColorScheme
import ru.vasev.glasses.profile.AnswerStyle
import ru.vasev.glasses.profile.Formality
import ru.vasev.glasses.profile.SpeakerGender
import ru.vasev.glasses.profile.SpeechPace
import ru.vasev.glasses.ui.AppRoot
import ru.vasev.glasses.ui.AppTab
import ru.vasev.glasses.ui.DevScreen
import ru.vasev.glasses.ui.glass.GlassRoot
import ru.vasev.glasses.ui.OnboardingScreen
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
        // Первый показ — стартовая вкладка роли; после пересоздания — та, что была открыта.
        tab = savedInstanceState?.getString(STATE_TAB)?.let { name -> AppTab.entries.firstOrNull { it.name == name } }
            ?: profile.role.value.startTab
        settingsOpen = savedInstanceState?.getBoolean(STATE_SETTINGS) ?: false
        developerOpen = settingsOpen && savedInstanceState?.getBoolean(STATE_DEVELOPER) == true
        enableEdgeToEdge()
        // До первого кадра Compose система показывает фон окна: пусть он будет цвета нашего градиента, а не чёрный/белый.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(glassesColorScheme(this, night).surface.toArgb()))
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
            handleDebugIntent(intent)
        }
        requestRuntimePermissions()

        setContent {
            GlassesTheme {
                val onboarded by profile.onboarded.collectAsStateWithLifecycle()
                val role by profile.role.collectAsStateWithLifecycle()
                val lessTransparency by profile.lessTransparency.collectAsStateWithLifecycle()
                if (!onboarded) {
                    val language by settings.language.collectAsStateWithLifecycle()
                    GlassRoot(denser = lessTransparency, animate = true) {
                        OnboardingScreen(
                            language = language,
                            systemLanguage = LanguageSettings.system(),
                            onLanguage = settings::setLanguage,
                            initialRole = role,
                            onFinish = { chosen ->
                                profile.completeOnboarding(chosen)
                                selectTab(chosen.startTab)
                            },
                        )
                    }
                    return@GlassesTheme
                }
                // Роль сменили в настройках, а открытой вкладки у новой роли нет — на стартовую.
                LaunchedEffect(role) { if (tab !in role.tabs) selectTab(role.startTab) }
                AppRoot(
                    tabs = role.tabs,
                    tab = role.keep(tab),
                    onTabChange = ::selectTab,
                    denserGlass = lessTransparency,
                    settingsOpen = settingsOpen,
                    onToggleSettings = {
                        settingsOpen = !settingsOpen
                        developerOpen = false
                    },
                    assistant = {
                        MainScreen(
                            viewModel = viewModel,
                            onAsk = { withMic(viewModel::ask) },
                            onWhatDoISee = viewModel::whatDoISee,
                            onOpenCamera = { selectTab(AppTab.CAMERA) },
                            onOpenSettings = { settingsOpen = true },
                        )
                    },
                    camera = {
                        CameraScreen(
                            viewModel = viewModel,
                            cameraGranted = cameraGranted,
                            onSelectSource = ::selectImageSource,
                            onConnectGlasses = { (application as GlassesApp).glasses.connect(this) },
                            onDescribe = viewModel::cameraDescribe,
                            onAsk = { withMic(viewModel::cameraAsk) },
                            onTranslate = viewModel::cameraTranslate,
                            onRemember = { withMic(viewModel::cameraRemember) },
                            onFollowUp = { withMic(viewModel::cameraFollowUp) },
                        )
                        AssistantOverlays(viewModel)
                    },
                    translation = {
                        TranslationScreen(
                            viewModel = translation,
                            onPartnerSpeaks = { withMic(translation::partnerSpeaks) },
                            onMeReplies = { withMic(translation::meReplies) },
                            onInstallVoice = ::openVoiceInstaller,
                            onOpenSettings = { settingsOpen = true },
                        )
                    },
                    navigation = {
                        NavigationScreen(
                            vms = NavViewModels(navSession, addressSearch, navTarget, taxi, places),
                            onMic = { withMic(addressSearch::listenQuery) },
                            onOpenLink = { openMapsLink(this, it) },
                            onLaunchApp = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) },
                        )
                    },
                    settings = {
                        if (developerOpen) {
                            val journal = (application as GlassesApp).journal
                            DevScreen(journal.entries, onExport = ::shareJournal, onClear = journal::clear, onClose = { developerOpen = false })
                        } else {
                            SettingsScreen(settings, onOpenDeveloper = { developerOpen = true })
                        }
                    },
                )
            }
        }
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
        handleRegistrationIntent(intent)
        handleDebugIntent(intent)
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
     *  - стиль:     --es style_answer guide --es style_formality casual --es style_gender male|female|unset --es style_pace slower
     *  - сбой:      --es fail_claude server|network|forbidden|ratelimit (следующий запрос к Claude)
     *  - снимки:    --es image_source glasses|phone|simulator
     *  - вкладка:    --es tab assistant|camera|translate|navigation|settings|development (журнал в настройках)
     *  - навигация: --es nav_to "Wat Arun" (поиск цели, как в поле «Куда?»)
     *  - компас за столом: --es fake_location "55.7539,37.6208" (подменить место, каждый интент — новая точка)
 *  - первый запуск: --ez reset_onboarding true (данные приложения не стираются)
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
