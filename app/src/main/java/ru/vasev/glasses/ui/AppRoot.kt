package ru.vasev.glasses.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
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
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassRoot
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent

enum class AppTab(@StringRes val titleRes: Int, @DrawableRes val icon: Int) {
    ASSISTANT(R.string.tab_assistant, R.drawable.ic_sym_assistant),
    CAMERA(R.string.tab_camera, R.drawable.ic_sym_photo_camera),
    TRANSLATE(R.string.tab_translate, R.drawable.ic_sym_g_translate),
    NAVIGATION(R.string.tab_navigation, R.drawable.ic_sym_navigation),
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
    /** Настройки открыты поверх вкладки (шестерёнка справа от вкладок). */
    settingsOpen: Boolean,
    onToggleSettings: () -> Unit,
    assistant: @Composable () -> Unit,
    camera: @Composable () -> Unit,
    translation: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    // В режиме перевода фон застывает: телефон держат перед собеседником, лишняя анимация ни к чему.
    GlassRoot(denser = denserGlass, animate = tab != AppTab.TRANSLATE) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            val inset: Dp = TAB_BAR_HEIGHT + TAB_BAR_MARGIN * 2
            CompositionLocalProvider(LocalTabBarInset provides inset) {
                Box(Modifier.fillMaxSize()) {
                    if (settingsOpen) {
                        BackHandler(onBack = onToggleSettings)
                        settings()
                    } else {
                        when (tab) {
                            AppTab.ASSISTANT -> assistant()
                            AppTab.CAMERA -> camera()
                            AppTab.TRANSLATE -> translation()
                            AppTab.NAVIGATION -> navigation()
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(TAB_BAR_MARGIN),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Пока открыты настройки, ни одна вкладка не выбрана: нажатие на вкладку их закрывает.
                GlassTabBar(tabs, tab.takeUnless { settingsOpen }, onTabChange, Modifier.weight(1f))
                GlassIconButton(
                    R.drawable.ic_sym_settings,
                    stringResource(if (settingsOpen) R.string.settings_close else R.string.tab_settings),
                    onClick = onToggleSettings,
                    size = 52.dp,
                    blur = true,
                    kind = if (settingsOpen) GlassButtonKind.PRIMARY else GlassButtonKind.NORMAL,
                )
            }
        }
    }
}

/** Плавающая стеклянная панель-пилюля; выбранная вкладка — внутренняя пилюля акцентного цвета. */
@Composable
private fun GlassTabBar(tabs: List<AppTab>, tab: AppTab?, onTabChange: (AppTab) -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    // Подпись выбранной вкладки лежит ещё и на пилюле акцентного цвета — контраст считается с её учётом.
    val selectedTint = c.primary.copy(alpha = SELECTED_ALPHA).compositeOver(c.surface)
    val accent = glassAccent(tint = selectedTint, overContent = true)
    // При четырёх вкладках и крупном шрифте подпись уменьшается до ширины вкладки, одинаково для всех.
    val maxLabel = if (tabs.size > 3) MaterialTheme.typography.labelMedium.fontSize else MaterialTheme.typography.labelLarge.fontSize
    Row(
        modifier
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
                    // С переносом, но в одну строку: так autoSize видит, что слово не влезло, и уменьшает шрифт
                    // (с softWrap = false текст просто обрезался краем вкладки — панель стала уже из-за шестерёнки).
                    softWrap = true,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = maxLabel),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
