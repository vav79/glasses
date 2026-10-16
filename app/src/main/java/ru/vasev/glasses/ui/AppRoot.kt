package ru.vasev.glasses.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class AppTab(val title: String) { ASSISTANT("Ассистент"), TRANSLATE("Перевод") }

@Composable
fun AppRoot(
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    assistant: @Composable () -> Unit,
    translation: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.fillMaxWidth()) {
                AppTab.entries.forEach { item ->
                    Tab(selected = item == tab, onClick = { onTabChange(item) }, text = { Text(item.title) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    AppTab.ASSISTANT -> assistant()
                    AppTab.TRANSLATE -> translation()
                }
            }
        }
    }
}
