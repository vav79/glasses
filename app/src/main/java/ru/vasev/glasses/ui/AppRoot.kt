package ru.vasev.glasses.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import ru.vasev.glasses.R

enum class AppTab(val title: String, @DrawableRes val icon: Int) {
    ASSISTANT("Ассистент", R.drawable.ic_sym_assistant),
    TRANSLATE("Перевод", R.drawable.ic_sym_g_translate),
    SETTINGS("Настройки", R.drawable.ic_sym_settings),
}

@Composable
fun AppRoot(
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    assistant: @Composable () -> Unit,
    translation: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.fillMaxWidth()) {
                AppTab.entries.forEach { item ->
                    Tab(
                        selected = item == tab,
                        onClick = { onTabChange(item) },
                        // Подпись вкладки озвучивает TalkBack, иконка — декоративная.
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        text = { Text(item.title) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    AppTab.ASSISTANT -> assistant()
                    AppTab.TRANSLATE -> translation()
                    AppTab.SETTINGS -> settings()
                }
            }
        }
    }
}
