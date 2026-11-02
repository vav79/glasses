package ru.vasev.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.profile.AssistantProfile
import ru.vasev.glasses.profile.Skill
import ru.vasev.glasses.profile.SkillsSettings
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.theme.LocalSputnikColors

/**
 * «Умения»: профили ассистента (свой промпт) и умения (Переводчик, Навигация). У каждого — переключатель
 * и вход в настройки; выключенное не показывается в чипе профиля и в меню.
 */
@Composable
fun SkillsScreen(skills: SkillsSettings, onOpenSkill: (Skill) -> Unit) {
    val disabled by skills.disabled.collectAsState()
    val active by skills.active.collectAsState()
    var editing by rememberSaveable { mutableStateOf<AssistantProfile?>(null) }
    editing?.let { profile ->
        BackHandler { editing = null }
        ProfilePromptEditor(profile, skills, onClose = { editing = null })
        return
    }
    val p = LocalSputnikColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel(stringResource(R.string.skills_profiles))
        Text(stringResource(R.string.skills_profiles_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        AssistantProfile.entries.forEach { profile ->
            SkillRow(
                icon = profile.icon,
                title = stringResource(profile.titleRes),
                description = stringResource(if (profile == active) R.string.skills_active else profile.descriptionRes),
                enabled = profile.name !in disabled,
                onToggle = { skills.setEnabled(profile.name, it) },
                onOpen = { editing = profile },
            )
        }
        SectionLabel(stringResource(R.string.skills_skills))
        Skill.entries.forEach { skill ->
            SkillRow(
                icon = skill.icon,
                title = stringResource(skill.titleRes),
                description = stringResource(skill.descriptionRes),
                enabled = skill.name !in disabled,
                onToggle = { skills.setEnabled(skill.name, it) },
                onOpen = { onOpenSkill(skill) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalSputnikColors.current.text2,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** Строка умения: нажатие — настройки, переключатель — вкл/выкл. */
@Composable
private fun SkillRow(@DrawableRes icon: Int, title: String, description: String, enabled: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val p = LocalSputnikColors.current
    GlassCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onOpen).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = if (enabled) p.text else p.iconMuted, modifier = Modifier.size(24.dp))
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = p.text2)
                }
                Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null, tint = p.text2)
            }
            Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

/** Настройки профиля: системный промпт (подставляется перед каждым запросом). Остальные настройки профилей — позже. */
@Composable
private fun ProfilePromptEditor(profile: AssistantProfile, skills: SkillsSettings, onClose: () -> Unit) {
    val p = LocalSputnikColors.current
    var text by rememberSaveable(profile) { mutableStateOf(skills.prompt(profile)) }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(profile.icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Text(stringResource(profile.titleRes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).semantics { heading() })
        }
        Text(stringResource(R.string.skills_prompt_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(SkillsSettings.MAX_PROMPT) },
            label = { Text(stringResource(R.string.skills_prompt)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(stringResource(R.string.skills_prompt_reset), onClick = { text = profile.defaultPrompt }, modifier = Modifier.weight(1f))
            GlassButton(
                stringResource(R.string.style_save),
                onClick = {
                    skills.setPrompt(profile, text)
                    onClose()
                },
                kind = GlassButtonKind.PRIMARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
