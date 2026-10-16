package ru.vasev.glasses.translate

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.claude.Anthropic
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.Heard
import ru.vasev.glasses.speech.Speaker
import ru.vasev.glasses.speech.SpeechException
import ru.vasev.glasses.speech.VoiceStatus
import java.util.Locale

enum class TranslatePhase(val label: String) {
    IDLE(""),
    LISTENING_PARTNER("Слушаю собеседника…"),
    LISTENING_ME("Слушаю вас…"),
    TRANSLATING("Перевожу…"),
    SPEAKING("Озвучиваю перевод…"),
}

data class FeedLine(val id: Long, val line: Line)

data class TranslateUiState(
    val language: PartnerLanguage = PartnerLanguage.ENGLISH,
    val continuous: Boolean = false,
    val phase: TranslatePhase = TranslatePhase.IDLE,
    val feed: List<FeedLine> = emptyList(),
    /** Распознаётся прямо сейчас (частичный результат). */
    val pending: String = "",
    val pendingWho: Who? = null,
    val error: String? = null,
    /** Подсказка, что голос TTS для языка не скачан. */
    val voiceHint: String? = null,
)

/** Режим перевода живого разговора. Отдельно от истории ассистента, живёт только в памяти. */
class TranslationViewModel(app: Application) : AndroidViewModel(app) {

    private val speech = (app as GlassesApp).speechSource
    private val audio = (app as GlassesApp).audioOutput
    private val translator = Translator()
    private val prefs = app.getSharedPreferences("translate", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        TranslateUiState(
            language = prefs.getString(KEY_LANGUAGE, null)
                ?.let { name -> PartnerLanguage.entries.firstOrNull { it.name == name } }
                ?: PartnerLanguage.ENGLISH,
            continuous = prefs.getBoolean(KEY_CONTINUOUS, false),
        ),
    )
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    val hasApiKey: Boolean get() = Anthropic.hasApiKey

    private val lines = mutableListOf<Line>()
    private var nextId = 0L
    private var job: Job? = null

    init {
        checkVoice(_state.value.language)
    }

    fun setLanguage(language: PartnerLanguage) {
        if (language == _state.value.language) return
        stop()
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        _state.update { it.copy(language = language, voiceHint = null, error = null) }
        checkVoice(language)
    }

    fun setContinuous(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTINUOUS, enabled).apply()
        _state.update { it.copy(continuous = enabled) }
    }

    /** «Собеседник говорит»: его речь → русский → озвучка мне. В непрерывном режиме — по кругу. */
    fun partnerSpeaks() = launch { turn(Who.PARTNER, preset = null, quietNoSpeech = false) }

    /** «Я отвечаю»: моя речь → язык собеседника → озвучка ему. В непрерывном режиме затем слушаю собеседника. */
    fun meReplies() = launch { turn(Who.ME, preset = null, quietNoSpeech = false) }

    /** Только для debug-сборок: реплика с готовым текстом вместо распознавания. */
    fun debugTurn(who: Who, text: String) = launch { turn(who, preset = text, quietNoSpeech = false) }

    fun stop() {
        job?.cancel()
        job = null
        audio.stop()
        _state.update { it.copy(phase = TranslatePhase.IDLE, pending = "", pendingWho = null) }
    }

    fun clearFeed() {
        stop()
        lines.clear()
        _state.update { it.copy(feed = emptyList(), error = null) }
    }

    private fun launch(first: suspend () -> Unit) {
        job?.cancel()
        audio.stop()
        _state.update { it.copy(error = null) }
        job = viewModelScope.launch {
            try {
                first()
                // Непрерывный режим: снова слушаем собеседника, пока не нажат «Стоп».
                while (_state.value.continuous) {
                    turn(Who.PARTNER, preset = null, quietNoSpeech = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                Log.d(LOG_TAG, "Speech: ${e.message}")
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Translation failed", e)
                _state.update { it.copy(error = e.message ?: "Что-то пошло не так.") }
            } finally {
                _state.update { it.copy(phase = TranslatePhase.IDLE, pending = "", pendingWho = null) }
            }
        }
    }

    /**
     * Одна реплика: распознавание → фильтр шума → перевод → озвучка.
     * Микрофон включается только после окончания предыдущей озвучки плюс пауза [ECHO_GAP_MS] — защита от эха.
     */
    private suspend fun turn(who: Who, preset: String?, quietNoSpeech: Boolean) {
        val language = _state.value.language
        val sourceTag = if (who == Who.PARTNER) language.tag else RU_TAG

        val t0 = SystemClock.elapsedRealtime()
        if (preset == null) Log.d(LOG_TAG, "[$who] mic on ($sourceTag)")
        _state.update {
            it.copy(
                phase = if (who == Who.PARTNER) TranslatePhase.LISTENING_PARTNER else TranslatePhase.LISTENING_ME,
                pending = "",
                pendingWho = who,
            )
        }
        val heard = try {
            if (preset != null) {
                Heard(preset, SystemClock.elapsedRealtime())
            } else {
                speech.listen(sourceTag) { partial -> _state.update { it.copy(pending = partial) } }
            }
        } catch (e: SpeechException) {
            if ((e.noSpeech || e.transient) && quietNoSpeech) {
                Log.d(LOG_TAG, "[$who] ${if (e.noSpeech) "no speech" else "recognizer hiccup"}, listening again")
                if (e.transient) delay(RETRY_DELAY_MS)
                return
            }
            throw e
        }
        val tRecognized = SystemClock.elapsedRealtime()
        Log.d(LOG_TAG, "[$who] recognition=${tRecognized - t0}ms text=\"${heard.text}\"")

        if (NoiseFilter.isNoise(heard.text)) {
            Log.d(LOG_TAG, "[$who] skipped as noise")
            _state.update { it.copy(pending = "", pendingWho = null) }
            if (!quietNoSpeech) throw SpeechException("Не расслышал фразу. Попробуйте ещё раз.", noSpeech = true)
            return
        }

        _state.update { it.copy(phase = TranslatePhase.TRANSLATING, pending = heard.text) }
        val prompt = TranslationPrompt.build(language, who, heard.text, lines)
        val translation = translator.translate(prompt)
        val tTranslated = SystemClock.elapsedRealtime()
        Log.d(LOG_TAG, "[$who] translate=${tTranslated - tRecognized}ms result=\"$translation\"")

        val line = Line(who, heard.text, translation)
        lines += line
        _state.update { it.copy(feed = it.feed + FeedLine(nextId++, line), pending = "", pendingWho = null) }
        if (translation == null) return

        val targetLocale: Locale = if (who == Who.PARTNER) Speaker.RUSSIAN else language.locale
        if (who == Who.ME && audio.voiceStatus(targetLocale) != VoiceStatus.READY) {
            showVoiceHint(language, audio.voiceStatus(targetLocale))
            return
        }

        _state.update { it.copy(phase = TranslatePhase.SPEAKING) }
        val channel = if (who == Who.PARTNER) AudioChannel.ME else AudioChannel.PARTNER
        audio.speak(translation, targetLocale, channel) {
            val now = SystemClock.elapsedRealtime()
            Log.d(
                LOG_TAG,
                "[$who] tts_start=${now - tTranslated}ms after translation; " +
                    "end_of_speech→sound=${now - heard.endOfSpeechAt}ms",
            )
        }
        // Озвучка закончилась (onDone). Пауза перед тем, как снова включить микрофон, чтобы не поймать эхо.
        Log.d(LOG_TAG, "[$who] tts done, mic pause ${ECHO_GAP_MS}ms")
        delay(ECHO_GAP_MS)
    }

    private fun checkVoice(language: PartnerLanguage) {
        viewModelScope.launch {
            val status = audio.voiceStatus(language.locale)
            Log.d(LOG_TAG, "TTS voice for ${language.tag}: $status")
            if (status != VoiceStatus.READY) showVoiceHint(language, status)
        }
    }

    private fun showVoiceHint(language: PartnerLanguage, status: VoiceStatus) {
        val hint = when (status) {
            VoiceStatus.NOT_DOWNLOADED ->
                "Голос «${language.title}» не скачан — ответы собеседнику не будут озвучены. " +
                    "Скачайте его кнопкой ниже (или: Настройки → Общие настройки → Синтез речи)."
            VoiceStatus.UNSUPPORTED ->
                "Движок синтеза речи не поддерживает язык «${language.title}». Выберите Google в " +
                    "Настройки → Общие настройки → Синтез речи и скачайте голос."
            VoiceStatus.READY -> null
        }
        _state.update { it.copy(voiceHint = hint) }
    }

    override fun onCleared() {
        audio.stop()
    }

    private companion object {
        const val LOG_TAG = "Translate"
        const val RU_TAG = "ru-RU"
        const val ECHO_GAP_MS = 400L
        const val RETRY_DELAY_MS = 300L
        const val KEY_LANGUAGE = "language"
        const val KEY_CONTINUOUS = "continuous"
    }
}
