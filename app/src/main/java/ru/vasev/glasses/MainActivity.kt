package ru.vasev.glasses

import android.Manifest
import android.content.Intent
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
import ru.vasev.glasses.translate.PartnerLanguage
import ru.vasev.glasses.translate.TranslationViewModel
import ru.vasev.glasses.translate.Who
import ru.vasev.glasses.ui.AppRoot
import ru.vasev.glasses.ui.AppTab
import ru.vasev.glasses.ui.DevScreen
import ru.vasev.glasses.ui.OnboardingScreen
import ru.vasev.glasses.ui.MainScreen
import ru.vasev.glasses.ui.MainViewModel
import ru.vasev.glasses.ui.SettingsScreen
import ru.vasev.glasses.ui.SettingsViewModel
import ru.vasev.glasses.ui.TranslationScreen
import ru.vasev.glasses.ui.theme.GlassesTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val translation: TranslationViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    private var tab by mutableStateOf(AppTab.ASSISTANT)

    /** Есть ли доступ к камере телефона; перепроверяется при каждом возврате в приложение (могли отозвать в настройках). */
    private var cameraGranted by mutableStateOf(false)

    private var pendingMicAction: (() -> Unit)? = null
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null

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

    /** Системный выбор фото: приложение получает только выбранный снимок, без доступа ко всей галерее. */
    private var pendingPhoto: CompletableDeferred<Uri?>? = null
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
        enableEdgeToEdge()
        viewModel.cameraPermissionRequester = ::requestGlassesCamera
        viewModel.galleryPicker = ::pickPhoto
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
                if (!onboarded) {
                    val language by settings.language.collectAsStateWithLifecycle()
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
                    return@GlassesTheme
                }
                // Роль сменили в настройках, а открытой вкладки у новой роли нет — на стартовую.
                LaunchedEffect(role) { if (tab !in role.tabs) selectTab(role.startTab) }
                AppRoot(
                    tabs = role.tabs,
                    tab = role.keep(tab),
                    onTabChange = ::selectTab,
                    assistant = {
                        MainScreen(
                            viewModel = viewModel,
                            onAsk = { withMic(viewModel::ask) },
                            onWhatDoISee = { withMic(viewModel::whatDoISee) },
                            onConnectGlasses = { Wearables.startRegistration(this) },
                            onOpenSettings = { selectTab(AppTab.SETTINGS) },
                            onSelectSource = ::selectImageSource,
                            cameraGranted = cameraGranted,
                        )
                    },
                    translation = {
                        TranslationScreen(
                            viewModel = translation,
                            onPartnerSpeaks = { withMic(translation::partnerSpeaks) },
                            onMeReplies = { withMic(translation::meReplies) },
                            onInstallVoice = ::openVoiceInstaller,
                            onOpenSettings = { selectTab(AppTab.SETTINGS) },
                        )
                    },
                    development = {
                        val journal = (application as GlassesApp).journal
                        DevScreen(journal.entries, onExport = ::shareJournal, onClear = journal::clear)
                    },
                    settings = { SettingsScreen(settings) },
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
    private fun selectTab(newTab: AppTab) {
        if (newTab == tab) return
        when (tab) {
            AppTab.ASSISTANT -> viewModel.stop()
            AppTab.TRANSLATE -> translation.stop()
            AppTab.DEVELOPMENT, AppTab.SETTINGS -> Unit
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
    }

    override fun onResume() {
        super.onResume()
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        viewModel.cameraPermissionRequester = null
        viewModel.galleryPicker = null
        pendingCameraPermission?.complete(false)
        pendingPhoto?.complete(null)
        super.onDestroy()
    }

    /**
     * Debug-запуск сценариев без голоса:
     *  - ассистент: --es question "Текст" [--ez with_frame true]
     *  - перевод:   --es translate_partner "Merhaba" | --es translate_me "Привет" [--es language TURKISH]
     *  - модели:    --es model_assistant deepseek-v4-pro | --es model_translation deepseek-flash
     *  - сбой:      --es fail_claude server|network|forbidden|ratelimit (следующий запрос к Claude)
     *  - снимки:    --es image_source glasses|phone|gallery|simulator
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
        intent.getStringExtra("image_source")?.let { name ->
            intent.removeExtra("image_source")
            val source = when (name) {
                "glasses" -> ImageSource.GLASSES
                "phone" -> ImageSource.PHONE_CAMERA
                "gallery" -> ImageSource.GALLERY
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
        intent.getStringExtra("fail_claude")?.let { kind ->
            intent.removeExtra("fail_claude")
            DebugFaults.arm(ProviderId.ANTHROPIC, kind)
        }
        intent.getStringExtra("question")?.let { question ->
            intent.removeExtra("question")
            selectTab(AppTab.ASSISTANT)
            viewModel.debugAsk(question, intent.getBooleanExtra("with_frame", false))
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
                .onFailure { error, _ -> Log.e(TAG, "Registration failed: ${error.description}") }
        }
    }

    private suspend fun requestGlassesCamera(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission?.complete(false)
        pendingCameraPermission = deferred
        cameraPermission.launch(Permission.CAMERA)
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
    }
}
