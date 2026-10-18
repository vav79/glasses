package ru.vasev.glasses.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.GlassRoot
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent

enum class AppTab(@StringRes val titleRes: Int, @DrawableRes val icon: Int) {
    ASSISTANT(R.string.tab_assistant, R.drawable.ic_sym_assistant),
    TRANSLATE(R.string.tab_translate, R.drawable.ic_sym_g_translate),
    DEVELOPMENT(R.string.tab_dev, R.drawable.ic_sym_code),
    SETTINGS(R.string.tab_settings, R.drawable.ic_sym_settings),
}

/**
 * Высота плавающей панели вкладок с отступами: прокручиваемое содержимое начинается ниже неё,
 * а при прокрутке уходит под неё (панель его размывает).
 */
val LocalTabBarInset = compositionLocalOf { 0.dp }

private val TAB_BAR_HEIGHT = 64.dp
private val TAB_BAR_MARGIN = 8.dp
private const val SELECTED_ALPHA = 0.16f

@Composable
fun AppRoot(
    /** Вкладки по порядку — задаёт роль. */
    tabs: List<AppTab>,
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    /** «Меньше прозрачности» из настроек. */
    denserGlass: Boolean,
    assistant: @Composable () -> Unit,
    translation: @Composable () -> Unit,
    development: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    // В режиме перевода фон застывает: телефон держат перед собеседником, лишняя анимация ни к чему.
    GlassRoot(denser = denserGlass, animate = tab != AppTab.TRANSLATE) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            val inset: Dp = TAB_BAR_HEIGHT + TAB_BAR_MARGIN * 2
            CompositionLocalProvider(LocalTabBarInset provides inset) {
                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        AppTab.ASSISTANT -> assistant()
                        AppTab.TRANSLATE -> translation()
                        AppTab.DEVELOPMENT -> development()
                        AppTab.SETTINGS -> settings()
                    }
                }
            }
            GlassTabBar(tabs, tab, onTabChange, Modifier.align(Alignment.TopCenter).padding(TAB_BAR_MARGIN))
        }
    }
}

/** Плавающая стеклянная панель-пилюля; выбранная вкладка — внутренняя пилюля акцентного цвета. */
@Composable
private fun GlassTabBar(tabs: List<AppTab>, tab: AppTab, onTabChange: (AppTab) -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    // Подпись выбранной вкладки лежит ещё и на пилюле акцентного цвета — контраст считается с её учётом.
    val selectedTint = c.primary.copy(alpha = SELECTED_ALPHA).compositeOver(c.surface)
    val accent = glassAccent(tint = selectedTint, overContent = true)
    // При четырёх вкладках и крупном шрифте подпись уменьшается до ширины вкладки, одинаково для всех.
    val maxLabel = if (tabs.size > 3) MaterialTheme.typography.labelMedium.fontSize else MaterialTheme.typography.labelLarge.fontSize
    Row(
        modifier
            .fillMaxWidth()
            .height(TAB_BAR_HEIGHT)
            .glass(GlassShapes.pill, blur = true)
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { item ->
            val selected = item == tab
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(GlassShapes.pill)
                    .then(if (selected) Modifier.background(c.primary.copy(alpha = SELECTED_ALPHA)) else Modifier)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onTabChange(item) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Подпись — всегда основным цветом текста: под панелью проезжает содержимое любой яркости, и акцентный
                // цвет мог бы опуститься ниже 4.5:1. Выбор видно по пилюле, жирному шрифту и акцентной иконке (ей хватает 3:1).
                val iconColor = if (selected) accent else c.onSurface
                // Подпись вкладки озвучивает TalkBack, иконка — декоративная.
                Icon(painterResource(item.icon), contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(item.titleRes),
                    color = c.onSurface,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = maxLabel),
                )
            }
        }
    }
}
