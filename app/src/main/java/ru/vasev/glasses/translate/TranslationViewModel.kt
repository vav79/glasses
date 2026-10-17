package ru.vasev.glasses.translate

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.speech.AudioChannel
import ru.vasev.glasses.speech.Heard
import ru.vasev.glasses.speech.SpeechException
import ru.vasev.glasses.speech.Talker
import ru.vasev.glasses.speech.VoiceStatus
import java.util.Locale

enum class TranslatePhase(@StringRes val labelRes: Int?) {
    IDLE(null),
    LISTENING_PARTNER(R.string.tphase_listening_partner),
    LISTENING_ME(R.string.tphase_listening_me),
    TRANSLATING(R.string.tphase_translating),
    SPEAKING(R.string.tphase_speaking),
}

/** Реплика в ленте; [model] — метка модели, которая перевела. */
data class FeedLine(val id: Long, val line: Line, val model: ModelTag? = null)

data class TranslateUiState(
    /** Язык собеседника. */
    val language: PartnerLanguage = PartnerLanguage.ENGLISH,
    /** Мой язык = язык приложения. */
    val my: PartnerLanguage = PartnerLanguage.RUSSIAN,
    val continuous: Boolean = false,
    val phase: TranslatePhase = TranslatePhase.IDLE,
    val feed: List<FeedLine> = emptyList(),
    /** Распознаётся прямо сейчас (частичный результат). */
    val pending: String = "",
    val pendingWho: Who? = null,
    val error: String? = null,
    /** Голос TTS для языка не скачан или не поддерживается — подсказку собирает экран. */
    val voiceHint: Pair<PartnerLanguage, VoiceStatus>? = null,
)

/** Режим перевода живого разговора. Отдельно от истории ассистента, живёт только в памяти. */
class TranslationViewModel(app: Application) : AndroidViewModel(app) {

    private val speech = (app as GlassesApp).speechSource
    private val audio = (app as GlassesApp).audioOutput
    private val llm = (app as GlassesApp).llm
    private val translator = Translator(llm)
    private val prefs = app.getSharedPreferences("translate", Context.MODE_PRIVATE)
    private val noiseFilter = NoiseFilter.load(app)

    private fun savedPartner(): PartnerLanguage? =
        prefs.getString(KEY_LANGUAGE, null)?.let { name -> PartnerLanguage.entries.firstOrNull { it.name == name } }

    private val _state = MutableStateFlow(
        PartnerLanguage.forApp(LanguageSettings.current.value).let { my ->
            TranslateUiState(
                language = PartnerLanguage.resolvePartner(savedPartner(), my),
                my = my,
                continuous = prefs.getBoolean(KEY_CONTINUOUS, false),
            )
        },
    )
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    /** Модель «Перевода» и есть ли для неё ключ. */
    val model: StateFlow<ModelSpec> = llm.model(Role.TRANSLATION)
    val hasApiKey: StateFlow<Boolean> = llm.ready(Role.TRANSLATION)
        .stateIn(viewModelScope, SharingStarted.Eagerly, (app as GlassesApp).apiKeys.has(model.value.provider))

    private val lines = mutableListOf<Line>()
    private var nextId = 0L
    private var job: Job? = null

    init {
        checkVoice(_state.value.language)
        // Сменили язык приложения — меняется «мой язык»; лента и контекст сохраняются.
        viewModelScope.launch {
            LanguageSettings.current.collect { appLanguage ->
                val my = PartnerLanguage.forApp(appLanguage)
                if (my == _state.value.my) return@collect
                val partner = PartnerLanguage.resolvePartner(savedPartner(), my)
                _state.update { it.copy(my = my, language = partner) }
                checkVoice(partner)
            }
        }
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

    /** «Собеседник говорит»: его речь → мой язык → озвучка мне. В непрерывном режиме — по кругу. */
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
            } catch (e: LlmException) {
                // «Нет интернета» и т. п. — ещё и голосом, но только мне (канал ME), не собеседнику.
                Log.w(LOG_TAG, "LLM: ${e.message}")
                _state.update { it.copy(error = e.message, phase = TranslatePhase.SPEAKING) }
                runCatching { audio.speak(e.message.orEmpty(), _state.value.my.locale, AudioChannel.ME) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Translation failed", e)
                _state.update { it.copy(error = e.message ?: L.s(R.string.something_went_wrong)) }
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
        val my = _state.value.my
        val sourceTag = if (who == Who.PARTNER) language.tag else my.tag

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
                speech.listen(
                    sourceTag,
                    if (who == Who.PARTNER) Talker.PARTNER else Talker.USER,
                    onPartial = { partial -> _state.update { it.copy(pending = partial) } },
                )
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

        if (noiseFilter.isNoise(heard.text)) {
            Log.d(LOG_TAG, "[$who] skipped as noise")
            _state.update { it.copy(pending = "", pendingWho = null) }
            if (!quietNoSpeech) throw SpeechException(L.s(R.string.speech_phrase_not_heard), noSpeech = true)
            return
        }

        _state.update { it.copy(phase = TranslatePhase.TRANSLATING, pending = heard.text) }
        val prompt = TranslationPrompt.build(language, my, who, heard.text, lines)
        val answer = translator.translate(prompt)
        val translation = answer.text
        val tTranslated = SystemClock.elapsedRealtime()
        Log.d(LOG_TAG, "[$who] translate=${tTranslated - tRecognized}ms model=${answer.model.id} result=\"$translation\"")

        val line = Line(who, heard.text, translation)
        lines += line
        // Запасная модель в переводе — только меткой: лишняя фраза вслух сломала бы разговор.
        _state.update { it.copy(feed = it.feed + FeedLine(nextId++, line, answer.tag), pending = "", pendingWho = null) }
        if (translation == null) return

        val targetLocale: Locale = if (who == Who.PARTNER) my.locale else language.locale
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
        _state.update { it.copy(voiceHint = if (status == VoiceStatus.READY) null else language to status) }
    }

    override fun onCleared() {
        audio.stop()
    }

    private companion object {
        const val LOG_TAG = "Translate"
        const val ECHO_GAP_MS = 400L
        const val RETRY_DELAY_MS = 300L
        const val KEY_LANGUAGE = "language"
        const val KEY_CONTINUOUS = "continuous"
    }
}
