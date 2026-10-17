package ru.vasev.glasses.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ru.vasev.glasses.R

enum class AppTab(@StringRes val titleRes: Int, @DrawableRes val icon: Int) {
    ASSISTANT(R.string.tab_assistant, R.drawable.ic_sym_assistant),
    TRANSLATE(R.string.tab_translate, R.drawable.ic_sym_g_translate),
    DEVELOPMENT(R.string.tab_dev, R.drawable.ic_sym_code),
    SETTINGS(R.string.tab_settings, R.drawable.ic_sym_settings),
}

@Composable
fun AppRoot(
    /** Вкладки по порядку — задаёт роль. */
    tabs: List<AppTab>,
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    assistant: @Composable () -> Unit,
    translation: @Composable () -> Unit,
    development: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // При четырёх вкладках — один размер подписей для всех, иначе короткие выглядят крупнее длинных.
            val maxLabel = if (tabs.size > 3) MaterialTheme.typography.labelMedium.fontSize else null
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), modifier = Modifier.fillMaxWidth()) {
                tabs.forEach { item ->
                    Tab(
                        selected = item == tab,
                        onClick = { onTabChange(item) },
                        // Подпись вкладки озвучивает TalkBack, иконка — декоративная.
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        text = {
                            // Четыре вкладки при крупном шрифте не помещаются — подпись уменьшается до ширины вкладки.
                            Text(
                                stringResource(item.titleRes),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = maxLabel ?: LocalTextStyle.current.fontSize),
                            )
                        },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    AppTab.ASSISTANT -> assistant()
                    AppTab.TRANSLATE -> translation()
                    AppTab.DEVELOPMENT -> development()
                    AppTab.SETTINGS -> settings()
                }
            }
        }
    }
}
