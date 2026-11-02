package ru.vasev.glasses.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.glassBackgroundText

/**
 * Экраны приложения. Главный — [ASSISTANT] (чат). [CAMERA], [TRANSLATE], [NAVIGATION] — старые вкладки:
 * пока не перенесены на новые места, открываются из бокового меню («Медиа», «Очки», «Умения»).
 */
enum class AppTab(@StringRes val titleRes: Int, @DrawableRes val icon: Int) {
    ASSISTANT(R.string.tab_assistant, R.drawable.ic_sym_assistant),
    CAMERA(R.string.tab_camera, R.drawable.ic_sym_photo_camera),
    TRANSLATE(R.string.tab_translate, R.drawable.ic_sym_g_translate),
    NAVIGATION(R.string.tab_navigation, R.drawable.ic_sym_navigation),
    SKILLS(R.string.menu_skills, R.drawable.ic_sym_auto_awesome),
    CALENDAR(R.string.menu_calendar, R.drawable.ic_sym_calendar_month),
}

/**
 * Отступ сверху для содержимого экранов. Была высота плавающей панели вкладок; шапка теперь стоит над
 * экраном, а не поверх него, поэтому отступ — небольшой.
 */
val LocalTabBarInset = compositionLocalOf { 8.dp }

/** «Умения» до этапа 4: старые экраны, которые станут умениями. */
@Composable
fun SkillsScreen(onOpen: (AppTab) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.skills_interim_hint), style = MaterialTheme.typography.bodyMedium, color = glassBackgroundText())
        for (tab in listOf(AppTab.TRANSLATE, AppTab.NAVIGATION, AppTab.CAMERA)) {
            GlassCard(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(tab) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(tab.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(if (tab == AppTab.TRANSLATE) R.string.skill_translator else tab.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp).weight(1f),
                    )
                    Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null)
                }
            }
        }
    }
}

/** «Календарь» — до этапа 5. */
@Composable
fun CalendarPlaceholder() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_sym_calendar_month), contentDescription = null, modifier = Modifier.size(48.dp), tint = glassBackgroundText())
        Text(stringResource(R.string.calendar_soon), style = MaterialTheme.typography.bodyLarge, color = glassBackgroundText(), modifier = Modifier.padding(top = 12.dp))
    }
}
