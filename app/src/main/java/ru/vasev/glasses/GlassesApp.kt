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
import ru.vasev.glasses.glasses.GlassesManager
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.llm.AiSettings
import ru.vasev.glasses.llm.LlmService
import ru.vasev.glasses.llm.RequestJournal
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.notes.NoteStore
import ru.vasev.glasses.profile.ProfileSettings
import ru.vasev.glasses.rates.CbrRates
import ru.vasev.glasses.rates.CurrencySettings
import ru.vasev.glasses.security.ApiKeyStore
import ru.vasev.glasses.speech.AudioFocus
import ru.vasev.glasses.speech.AudioOutput
import ru.vasev.glasses.speech.AudioRouter
import ru.vasev.glasses.speech.HeadsetButtons
import ru.vasev.glasses.speech.PhoneMicSpeechSource
import ru.vasev.glasses.speech.RoutedSpeechSource
import ru.vasev.glasses.speech.PhoneSpeakerOutput
import ru.vasev.glasses.speech.Speaker
import ru.vasev.glasses.speech.SpeechSource

class GlassesApp : Application() {

    /** Источник снимков для «Что я вижу» и камера телефона. */
    lateinit var imageSources: ImageSourceSettings
        private set
    lateinit var phoneCamera: PhoneCamera
        private set

    lateinit var glasses: GlassesManager
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

    /** Кнопка гарнитуры, пока звучит ответ ассистента. */
    lateinit var headsetButtons: HeadsetButtons
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

    /** Журнал запросов к моделям (вкладка «Разработка»). */
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

    /** Координаты для заметок и страна для валюты на ценниках. */
    lateinit var location: DeviceLocation
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Строки на языке приложения нужны уже при создании остальных объектов (сообщения об ошибках).
        L.init(this)
        apiKeys = ApiKeyStore(this)
        aiSettings = AiSettings(this)
        profile = ProfileSettings(this)
        journal = RequestJournal(this)
        llm = LlmService(this, apiKeys, aiSettings, journal) { profile.journalTexts.value }
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT init failed: ${error.description}") }
        glasses = GlassesManager(this)
        imageSources = ImageSourceSettings(this, glasses)
        phoneCamera = PhoneCamera(this)
        audioRouter = AudioRouter(this)
        speechSource = RoutedSpeechSource(PhoneMicSpeechSource(this), audioRouter)
        speaker = Speaker(this)
        audioOutput = PhoneSpeakerOutput(speaker, audioRouter, AudioFocus(this))
        headsetButtons = HeadsetButtons(this)
        notes = NoteStore(this)
        location = DeviceLocation(this)
        currency = CurrencySettings(this)
        rates = CbrRates(this, llm::isOnline)
        // Кеш курсов обновляется, пока есть сеть: за границей интернет бывает не всегда, а курс нужен у ценника.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { rates.refreshIfStale() }
                }
            },
        )
    }

    companion object {
        const val TAG = "Glasses"
    }
}
