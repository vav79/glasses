package ru.vasev.glasses.i18n

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Язык приложения — он же язык общения с ассистентом: интерфейс, распознавание речи, голос, ответы модели,
 * голосовые команды и «мой язык» в переводе. Английский — по умолчанию (values/), русский — values-ru/.
 */
enum class AppLanguage(
    /** Тег для per-app language. */
    val tag: String,
    /** Язык распознавания речи (SpeechRecognizer). */
    val speechTag: String,
    /** Название по-английски — для промптов модели. */
    val englishName: String,
) {
    ENGLISH("en", "en-US", "English"),
    RUSSIAN("ru", "ru-RU", "Russian"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(speechTag)

    companion object {
        /** Русский — для русской локали, всё остальное — английский (английский — язык по умолчанию). */
        fun from(locale: Locale?): AppLanguage = if (locale?.language == "ru") RUSSIAN else ENGLISH
    }
}

/** Выбор в настройках: системный язык или конкретный. */
enum class LanguageChoice(val tag: String?) { SYSTEM(null), RUSSIAN("ru"), ENGLISH("en") }

/**
 * Per-app language через AppCompatDelegate.setApplicationLocales: выбор виден и в системных настройках Android
 * (Приложения → Очки → Язык). На Android 13+ работает средствами системы, на 10–12 — через AppCompat.
 */
object LanguageSettings {

    private val _current = MutableStateFlow(effective())

    /** Действующий язык приложения; читать перед каждым прослушиванием и озвучкой — так язык меняется на лету. */
    val current: StateFlow<AppLanguage> = _current.asStateFlow()

    fun choice(): LanguageChoice {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return LanguageChoice.SYSTEM
        return if (locales[0]?.language == "ru") LanguageChoice.RUSSIAN else LanguageChoice.ENGLISH
    }

    /** Меняет язык приложения; Activity пересоздаётся, ViewModel (и история разговора) сохраняются. */
    fun set(choice: LanguageChoice) {
        val locales = choice.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
        refresh()
    }

    /** Пересчитать после смены языка приложения или системы (Activity.onCreate / onConfigurationChanged). */
    fun refresh() {
        _current.value = effective()
    }

    /** Язык, который даёт системная локаль, — для подписи «Системный (…)». */
    fun system(): AppLanguage = AppLanguage.from(systemLocale())

    // На Android 13+ при заданном языке приложения Resources.getSystem() отдаёт уже его — системный берём у LocaleManager.
    private fun systemLocale(): Locale? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            L.application.getSystemService(LocaleManager::class.java).systemLocales[0]
        } else {
            Resources.getSystem().configuration.locales[0]
        }

    private fun effective(): AppLanguage {
        val app = AppCompatDelegate.getApplicationLocales()
        val locale = if (!app.isEmpty) app[0] else systemLocale()
        return AppLanguage.from(locale)
    }
}

/**
 * Строки на текущем языке приложения для кода без доступа к экрану: озвучиваемые фразы, сообщения об ошибках.
 * Контекст с нужной локалью создаётся явно — так строки правильные и вне Activity, и на Android 10–12.
 */
object L {
    private lateinit var app: Application

    val application: Application get() = app
    private val contexts = mutableMapOf<AppLanguage, Context>()

    fun init(application: Application) {
        app = application
    }

    fun s(@StringRes id: Int, vararg args: Any): String = context().getString(id, *args)

    fun array(id: Int): List<String> = context().resources.getStringArray(id).toList()

    /** Контекст с языком приложения (кэшируется по языку). */
    fun context(language: AppLanguage = LanguageSettings.current.value): Context = synchronized(contexts) {
        contexts.getOrPut(language) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) }
            app.createConfigurationContext(config)
        }
    }
}
