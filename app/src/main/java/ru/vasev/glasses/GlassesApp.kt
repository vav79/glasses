package ru.vasev.glasses

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.vasev.glasses.camera.ImageSourceSettings
import ru.vasev.glasses.camera.PhoneCamera
import ru.vasev.glasses.camera.ShotStore
import ru.vasev.glasses.chats.ChatStore
import ru.vasev.glasses.glasses.GlassesManager
import ru.vasev.glasses.glasses.VoiceLaunch
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.AiSettings
import ru.vasev.glasses.llm.LlmService
import ru.vasev.glasses.llm.RequestJournal
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.nav.AddressGeocoder
import ru.vasev.glasses.nav.CompassController
import ru.vasev.glasses.nav.NavSession
import ru.vasev.glasses.nav.NavVoiceCommands
import ru.vasev.glasses.nav.NavigatorSettings
import ru.vasev.glasses.nav.TaxiAddresses
import ru.vasev.glasses.notes.NoteStore
import ru.vasev.glasses.places.PlaceStore
import ru.vasev.glasses.profile.ProfileSettings
import ru.vasev.glasses.profile.StyleSettings
import ru.vasev.glasses.rates.CbrRates
import ru.vasev.glasses.rates.CurrencySettings
import ru.vasev.glasses.security.ApiKeyStore
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.AudioFocus
import ru.vasev.glasses.speech.AudioOutput
import ru.vasev.glasses.speech.AudioRouter
import ru.vasev.glasses.speech.GestureSettings
import ru.vasev.glasses.speech.GlassesGestures
import ru.vasev.glasses.speech.MicSettings
import ru.vasev.glasses.speech.NavigatorVoice
import ru.vasev.glasses.speech.PhoneMicSpeechSource
import ru.vasev.glasses.speech.RoutedSpeechSource
import ru.vasev.glasses.speech.PhoneSpeakerOutput
import ru.vasev.glasses.speech.Speaker
import ru.vasev.glasses.speech.SpeechSource
import ru.vasev.glasses.ui.theme.AppearanceSettings

class GlassesApp : Application() {

    /** Источник снимков для «Что я вижу» и камера телефона. */
    lateinit var imageSources: ImageSourceSettings
        private set
    lateinit var phoneCamera: PhoneCamera
        private set

    lateinit var glasses: GlassesManager
        private set

    /** «Hey Meta, start Sputnik» — поток голосовых запусков от Meta AI. */
    lateinit var voiceLaunch: VoiceLaunch
        private set

    /** Общие для ассистента и переводчика источник речи и вывод звука — чтобы вкладки не спорили за звук. */
    lateinit var speechSource: SpeechSource
        private set
    lateinit var audioOutput: AudioOutput
        private set
    lateinit var speaker: Speaker
        private set

    /** Маршрут звука и гарнитура: микрофон для голосового «стоп» во время ответа. */
    lateinit var audioRouter: AudioRouter
        private set

    /** Говорит ли навигатор: наша озвучка ему уступает. */
    lateinit var navigatorVoice: NavigatorVoice
        private set

    /** Касания дужки очков (вопрос в покое) и кнопка гарнитуры (перебить ответ). */
    lateinit var gestures: GlassesGestures
        private set
    lateinit var gestureSettings: GestureSettings
        private set

    /** Зашифрованные API-ключи провайдеров ИИ (вводятся на экране «Настройки»). */
    lateinit var apiKeys: ApiKeyStore
        private set

    /** Выбор моделей по ролям и запасной вариант. */
    lateinit var aiSettings: AiSettings
        private set

    /** Роль, первый запуск и видимость расширенных настроек. */
    lateinit var profile: ProfileSettings
        private set

    /** Журнал запросов к моделям (Настройки → «Для разработчика»). */
    lateinit var journal: RequestJournal
        private set

    /** Запросы к ИИ для ассистента и перевода. */
    lateinit var llm: LlmService
        private set

    /** Заметки «Запомни» — только на телефоне. */
    lateinit var notes: NoteStore
        private set

    /** Курсы ЦБ РФ (кеш на день) и «Моя валюта». */
    lateinit var rates: CbrRates
        private set
    lateinit var currency: CurrencySettings
        private set

    /** Лента последних снимков «Камеры» — только на телефоне. */
    lateinit var shots: ShotStore
        private set

    /** Чем слушать мой голос: авто / гарнитура / телефон. */
    lateinit var micSettings: MicSettings
        private set

    /** Координаты для заметок и страна для валюты на ценниках. */
    lateinit var location: DeviceLocation
        private set

    /** Мои места и недавние цели — только на телефоне. */
    lateinit var places: PlaceStore
        private set

    /** «Компас»: подсказки голосом по GPS и компасу, работает при смене вкладки и с выключенным экраном. */
    lateinit var compass: CompassController
        private set

    /** Адреса моих мест для таксиста на местном языке (переводятся заранее, хранятся для офлайна). */
    lateinit var taxiAddresses: TaxiAddresses
        private set

    /** Какой навигатор открывать: Авто / Яндекс / Google. */
    lateinit var navigator: NavigatorSettings
        private set

    /** «Навигация»: что открыто (цель, такси, моё место), где я, общие действия экранов вкладки и голосовых команд. */
    lateinit var navSession: NavSession
        private set

    /** Голосовые команды навигации с любой вкладки — ответ голосом. */
    lateinit var navVoice: NavVoiceCommands
        private set

    /** «Стиль общения»: стиль ассистента, обращение и пол в переводе, скорость речи. */
    lateinit var style: StyleSettings
        private set

    /** История чатов ассистента — только на телефоне. */
    lateinit var chats: ChatStore
        private set

    /** Тема и размер шрифта. */
    lateinit var appearance: AppearanceSettings
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Строки на языке приложения нужны уже при создании остальных объектов (сообщения об ошибках).
        L.init(this)
        apiKeys = ApiKeyStore(this)
        aiSettings = AiSettings(this)
        profile = ProfileSettings(this)
        appearance = AppearanceSettings(this)
        style = StyleSettings(this)
        journal = RequestJournal(this)
        llm = LlmService(this, apiKeys, aiSettings, journal) { profile.journalTexts.value }
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT init failed: ${error.description}") }
        Log.d(TAG, "DAT config: ${if (BuildConfig.MWDAT_DEV_MODE) "developer mode (application id 0)" else "Developer Center application id"}")
        glasses = GlassesManager(this)
        voiceLaunch = VoiceLaunch(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        imageSources = ImageSourceSettings(this, glasses)
        phoneCamera = PhoneCamera(this)
        audioRouter = AudioRouter(this)
        micSettings = MicSettings(this)
        audioRouter.micChoice = { micSettings.choice.value }
        audioRouter.glassesNames = { glasses.deviceNames.value }
        // Имена очков DAT присылает позже, чем Bluetooth подключает очки, — тогда переопределяем, где очки.
        scope.launch(Dispatchers.Main) { glasses.deviceNames.collect { audioRouter.refresh() } }
        shots = ShotStore(this)
        speechSource = RoutedSpeechSource(PhoneMicSpeechSource(this), audioRouter)
        speaker = Speaker(this)
        navigatorVoice = NavigatorVoice(this)
        audioOutput = PhoneSpeakerOutput(speaker, audioRouter, AudioFocus(this), navigatorVoice) { style.current.value.pace }
        gestureSettings = GestureSettings(this)
        gestures = GlassesGestures(this, audioRouter, gestureSettings) { speaker.playingToPartner }
        // Жест есть, а экрана нет (приложение закрыто): вопрос задать нечем — говорим, что нужно открыть приложение.
        gestures.onNoScreen = {
            Log.d(TAG, "gesture: no screen, asking to open the app")
            scope.launch(Dispatchers.Main) {
                runCatching { audioOutput.speak(L.s(R.string.say_gesture_open_app), LanguageSettings.current.value.locale, AudioChannel.ME) }
            }
        }
        notes = NoteStore(this)
        chats = ChatStore(this)
        location = DeviceLocation(this)
        places = PlaceStore(this)
        compass = CompassController(this, audioOutput)
        navigator = NavigatorSettings(this)
        taxiAddresses = TaxiAddresses(places, AddressGeocoder(this, llm::isOnline), llm, navigator)
        navSession = NavSession(this)
        navVoice = NavVoiceCommands(this, navSession)
        currency = CurrencySettings(this)
        rates = CbrRates(this, llm::isOnline)
        // Кеш курсов обновляется, пока есть сеть: за границей интернет бывает не всегда, а курс нужен у ценника.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { rates.refreshIfStale() }
                    // Адреса моих мест для таксиста — переводим, пока есть сеть: за границей её бывает нет.
                    taxiAddresses.refreshMissing()
                }
            },
        )
    }

    companion object {
        const val TAG = "Glasses"
    }
}
