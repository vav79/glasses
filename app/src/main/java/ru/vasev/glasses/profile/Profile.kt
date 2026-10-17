package ru.vasev.glasses.profile

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.AppTab

/**
 * Роль — набор включённых модулей, а не замок: порядок вкладок, стартовая вкладка и видимость расширенных настроек.
 * Роль меняется в настройках в любой момент; история, ключи, модели и журнал при этом не трогаются.
 */
enum class UserRole(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val icon: Int,
    /** Вкладки по порядку; первая — стартовая. */
    val tabs: List<AppTab>,
    /** Расширенные настройки видны сразу, без кнопки «Показать расширенные». */
    val advancedAlwaysVisible: Boolean,
) {
    TRAVELER(
        R.string.role_traveler, R.string.role_traveler_desc, R.drawable.ic_sym_luggage,
        listOf(AppTab.TRANSLATE, AppTab.ASSISTANT, AppTab.SETTINGS), advancedAlwaysVisible = false,
    ),
    RESEARCHER(
        R.string.role_researcher, R.string.role_researcher_desc, R.drawable.ic_sym_explore,
        listOf(AppTab.ASSISTANT, AppTab.TRANSLATE, AppTab.SETTINGS), advancedAlwaysVisible = false,
    ),
    DEVELOPER(
        R.string.role_developer, R.string.role_developer_desc, R.drawable.ic_sym_code,
        listOf(AppTab.ASSISTANT, AppTab.TRANSLATE, AppTab.DEVELOPMENT, AppTab.SETTINGS), advancedAlwaysVisible = true,
    ),
    ;

    val startTab: AppTab get() = tabs.first()

    /** Текущая вкладка, если она есть у роли, иначе стартовая (например, «Разработка» после смены роли). */
    fun keep(tab: AppTab): AppTab = if (tab in tabs) tab else startTab

    companion object {
        val DEFAULT = RESEARCHER

        fun restore(name: String?): UserRole = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Первый запуск (язык → роль), роль и видимость расширенных настроек. Хранится в SharedPreferences `profile`. */
class ProfileSettings(context: Context) {

    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    private val _role = MutableStateFlow(UserRole.restore(prefs.getString(KEY_ROLE, null)))
    private val _showAdvanced = MutableStateFlow(prefs.getBoolean(KEY_SHOW_ADVANCED, false))
    private val _journalTexts = MutableStateFlow(prefs.getBoolean(KEY_JOURNAL_TEXTS, false))

    /** Пройден ли первый запуск. */
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()
    val role: StateFlow<UserRole> = _role.asStateFlow()

    /** «Показать расширенные» для путешественника и исследователя (разработчику они видны всегда). */
    val showAdvanced: StateFlow<Boolean> = _showAdvanced.asStateFlow()

    /** Сохранять в журнале запросов тексты вопроса и ответа. По умолчанию — только метаданные. */
    val journalTexts: StateFlow<Boolean> = _journalTexts.asStateFlow()

    fun completeOnboarding(role: UserRole) {
        setRole(role)
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboarded.value = true
    }

    /** Только debug: снова показать первый запуск. Роль, ключи и остальные данные не трогаются. */
    fun resetOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDED, false).apply()
        _onboarded.value = false
    }

    fun setRole(role: UserRole) {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
        _role.value = role
    }

    fun setShowAdvanced(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ADVANCED, show).apply()
        _showAdvanced.value = show
    }

    fun setJournalTexts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_JOURNAL_TEXTS, enabled).apply()
        _journalTexts.value = enabled
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_ROLE = "role"
        const val KEY_SHOW_ADVANCED = "show_advanced"
        const val KEY_JOURNAL_TEXTS = "journal_texts"
    }
}
