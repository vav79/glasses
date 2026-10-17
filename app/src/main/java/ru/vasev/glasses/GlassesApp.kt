package ru.vasev.glasses

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import ru.vasev.glasses.claude.Anthropic
import ru.vasev.glasses.glasses.GlassesManager
import ru.vasev.glasses.security.ApiKeyStore
import ru.vasev.glasses.speech.AudioOutput
import ru.vasev.glasses.speech.AudioRouter
import ru.vasev.glasses.speech.PhoneMicSpeechSource
import ru.vasev.glasses.speech.RoutedSpeechSource
import ru.vasev.glasses.speech.PhoneSpeakerOutput
import ru.vasev.glasses.speech.Speaker
import ru.vasev.glasses.speech.SpeechSource

class GlassesApp : Application() {

    lateinit var glasses: GlassesManager
        private set

    /** Общие для ассистента и переводчика источник речи и вывод звука — чтобы вкладки не спорили за звук. */
    lateinit var speechSource: SpeechSource
        private set
    lateinit var audioOutput: AudioOutput
        private set
    lateinit var speaker: Speaker
        private set

    /** Зашифрованный API-ключ Anthropic (вводится на экране «Настройки»). */
    lateinit var apiKeys: ApiKeyStore
        private set

    override fun onCreate() {
        super.onCreate()
        apiKeys = ApiKeyStore(this)
        Anthropic.init(apiKeys)
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT init failed: ${error.description}") }
        glasses = GlassesManager(this)
        val router = AudioRouter(this)
        speechSource = RoutedSpeechSource(PhoneMicSpeechSource(this), router)
        speaker = Speaker(this)
        audioOutput = PhoneSpeakerOutput(speaker, router)
    }

    companion object {
        const val TAG = "Glasses"
    }
}
