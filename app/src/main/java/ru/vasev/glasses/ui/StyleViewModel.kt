package ru.vasev.glasses.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.answer.SentenceSplitter
import ru.vasev.glasses.answer.SpokenAnswer
import ru.vasev.glasses.claude.AssistantClient
import ru.vasev.glasses.claude.ToolPlan
import ru.vasev.glasses.claude.Turn
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.llm.Effort
import ru.vasev.glasses.llm.LlmException
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.llm.TurnMetrics
import ru.vasev.glasses.profile.CommunicationStyle
import ru.vasev.glasses.profile.UserRole
import ru.vasev.glasses.speech.AudioChannel

/** Пробный ответ «Проверить»: идёт запрос/озвучка, суть, подробности, метка модели, ошибка. */
data class StyleTest(
    val running: Boolean = false,
    val answer: String? = null,
    val details: String? = null,
    val tag: ModelTag? = null,
    val error: String? = null,
)

/**
 * Шторка «Стиль общения»: черновик до «Сохранить», пробный ответ в стиле черновика. Пробный ответ в историю
 * разговора не попадает (запрос отдельный, без памяти) и в кэш промпта не пишется.
 */
class StyleViewModel(app: Application) : AndroidViewModel(app) {

    private val g = app as GlassesApp
    private val settings = g.style
    private val llm = g.llm
    private val abbreviations = SentenceSplitter.loadAbbreviations(app)

    val saved: StateFlow<CommunicationStyle> = settings.current

    /** Подсказка «укажите пол» в «Переводе» уже показана. */
    val genderHintShown: StateFlow<Boolean> = settings.genderHintShown

    fun markGenderHintShown() = settings.markGenderHintShown()

    private val _draft = MutableStateFlow(saved.value)
    val draft: StateFlow<CommunicationStyle> = _draft.asStateFlow()

    /** Есть несохранённые изменения. */
    val dirty: StateFlow<Boolean> = combine(_draft, saved) { d, s -> d != s }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _test = MutableStateFlow(StyleTest())
    val test: StateFlow<StyleTest> = _test.asStateFlow()

    /** Есть ключ для модели ассистента — «Проверить» активна. */
    val ready: StateFlow<Boolean> = llm.ready(Role.ASSISTANT).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _testQuestion = MutableStateFlow("")

    /** Свой пробный вопрос (только на время экрана); пусто — пример из ресурсов. */
    val testQuestion: StateFlow<String> = _testQuestion.asStateFlow()

    fun setTestQuestion(text: String) {
        _testQuestion.value = text.take(MAX_QUESTION)
    }

    private var testJob: Job? = null

    /** Шторка открылась — черновик с сохранённого, пробный ответ сброшен. */
    fun open() {
        _draft.value = saved.value
        stopTest()
        _test.value = StyleTest()
    }

    fun edit(transform: (CommunicationStyle) -> CommunicationStyle) = _draft.update(transform)

    fun setCustom(text: String) = _draft.update { it.copy(customPrompt = text.take(CommunicationStyle.MAX_CUSTOM)) }

    /** «Очистить» — стирает только свой промпт. */
    fun clearCustom() = _draft.update { it.copy(customPrompt = "") }

    fun save() {
        settings.save(_draft.value)
        _draft.value = settings.current.value
        Log.d(TAG, "saved answer=${_draft.value.answer} custom=${_draft.value.customPrompt.length} formality=${_draft.value.formality} gender=${_draft.value.gender} pace=${_draft.value.pace}")
    }

    /** Шторка закрыта без сохранения. */
    fun discard() {
        stopTest()
        _draft.value = saved.value
    }

    fun showTag(tag: ModelTag): Boolean =
        g.profile.role.value == UserRole.DEVELOPER || tag.viaFallback || tag.noWebSearch || tag.title != ModelCatalog.default(Role.ASSISTANT).title

    /** «Проверить»: свой или пример пробного вопроса в стиле черновика, суть озвучивается с черновой скоростью. Повторное нажатие — отмена. */
    fun toggleTest() {
        if (testJob?.isActive == true) return stopTest()
        val style = _draft.value
        val question = _testQuestion.value.trim().ifEmpty { L.s(R.string.style_test_question) }
        testJob = viewModelScope.launch {
            _test.value = StyleTest(running = true)
            try {
                val language = LanguageSettings.current.value
                val metrics = TurnMetrics(SystemClock.elapsedRealtime())
                val streamed = llm.stream(
                    role = Role.ASSISTANT,
                    system = AssistantClient.systemPrompt(language, NO_TOOLS, g.currency.currency.value, style),
                    turns = listOf(Turn.User(question)),
                    maxTokens = MAX_TOKENS,
                    effort = Effort.NONE,
                    metrics = metrics,
                    onFallback = {},
                    onText = {},
                    cache = false,
                )
                llm.finishTurn(streamed, metrics, interrupted = false)
                val spoken = SpokenAnswer(SentenceSplitter(abbreviations))
                spoken.feed(streamed.answer.text ?: L.s(R.string.say_cannot_answer))
                spoken.finish()
                _test.update {
                    it.copy(answer = spoken.summaryText, details = spoken.detailsText.ifEmpty { null }, tag = streamed.answer.tag)
                }
                g.audioOutput.speak(spoken.summaryText, language.locale, AudioChannel.ME, rate = style.pace.rate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                _test.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "style test failed: ${e.javaClass.simpleName}")
                _test.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _test.update { it.copy(running = false) }
            }
        }
    }

    private fun stopTest() {
        testJob?.cancel()
        testJob = null
        _test.update { it.copy(running = false) }
    }

    override fun onCleared() {
        stopTest()
    }

    private companion object {
        const val TAG = "Style"
        const val MAX_TOKENS = 1024L
        const val MAX_QUESTION = 500

        /** Пробный вопрос — без заметок, курсов и поиска: проверяем только стиль. */
        val NO_TOOLS = ToolPlan(notes = false, deleteNotes = false, rates = false, webSearch = false)
    }
}
