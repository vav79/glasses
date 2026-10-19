package ru.vasev.glasses.nav

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** «Навигатор»: Авто (в России — Яндекс Карты, за границей — Google Карты) / Яндекс / Google. SharedPreferences `navigation`. */
class NavigatorSettings(context: Context) {

    private val prefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(
        NavigatorChoice.entries.firstOrNull { it.name == prefs.getString(KEY_NAVIGATOR, null) } ?: NavigatorChoice.AUTO,
    )

    val choice: StateFlow<NavigatorChoice> = _choice.asStateFlow()

    fun setChoice(choice: NavigatorChoice) {
        prefs.edit().putString(KEY_NAVIGATOR, choice.name).apply()
        _choice.value = choice
    }

    private companion object {
        const val KEY_NAVIGATOR = "navigator"
    }
}
