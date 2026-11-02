package ru.vasev.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.vasev.glasses.R
import ru.vasev.glasses.profile.CommunicationStyle
import ru.vasev.glasses.profile.Formality
import ru.vasev.glasses.profile.SpeakerGender
import ru.vasev.glasses.speech.VoiceStatus
import ru.vasev.glasses.translate.PartnerLanguage
import ru.vasev.glasses.translate.PartnerMic
import ru.vasev.glasses.translate.TranslatePhase
import ru.vasev.glasses.translate.TranslateUiState
import ru.vasev.glasses.translate.TranslationPrompt
import ru.vasev.glasses.translate.Who
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.theme.LocalSputnikColors

/**
 * Реплика «Переводчика» в ленте. Собеседник — слева: перевод крупно, сказанное мелко. Я — справа:
 * перевод для собеседника и «Показать» — крупно на весь экран, развёрнуто к нему.
 */
@Composable
fun TranslatedBubble(line: TranslatedLine, onShow: (String) -> Unit) {
    val p = LocalSputnikColors.current
    val mine = line.who == Who.ME
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(if (mine) R.string.translate_line_me else R.string.translate_line_partner),
                style = MaterialTheme.typography.labelMedium,
                color = p.text2,
            )
            Text(
                line.translation ?: stringResource(R.string.translation_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (mine) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(line.original, style = MaterialTheme.typography.bodySmall, color = p.text2)
            if (mine && line.translation != null) {
                TextButton(onClick = { onShow(line.translation) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_sym_fullscreen), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.translate_show), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/**
 * Низ экрана, пока идёт перевод: что сейчас происходит, язык собеседника, пауза прослушивания собеседника,
 * моя фраза — текстом или кнопкой «Я» голосом.
 */
@Composable
fun TranslateBar(
    state: TranslateUiState,
    hasKey: Boolean,
    listeningPaused: Boolean,
    showGenderHint: Boolean,
    onLanguage: (PartnerLanguage) -> Unit,
    onPauseToggle: () -> Unit,
    onMeSpeak: () -> Unit,
    onMeType: (String) -> Unit,
    onInstallVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = LocalSputnikColors.current
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isBlank()) return
        onMeType(text)
        text = ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.voiceHint?.let { (language, status) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (status == VoiceStatus.UNSUPPORTED) R.string.voice_unsupported else R.string.voice_not_downloaded, stringResource(language.titleRes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.text2,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstallVoice) { Text(stringResource(R.string.download_voice)) }
            }
        }
        if (showGenderHint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.translate_gender_hint), style = MaterialTheme.typography.bodySmall, color = p.text2, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.translate_gender_hint_open)) }
            }
        }
        val status = when {
            state.pending.isNotEmpty() -> state.pending
            listeningPaused && state.phase == TranslatePhase.IDLE -> stringResource(R.string.translate_paused)
            else -> state.phase.labelRes?.let { stringResource(it) } ?: stringResource(R.string.translate_waiting)
        }
        Text(status, style = MaterialTheme.typography.labelLarge, color = p.text2, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.err, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageButton(state, onLanguage)
            // Собеседника слушаем по кругу; кнопка — пауза и продолжение (зелёная, пока слушаем).
            val listening = !listeningPaused
            val pauseLabel = stringResource(if (listening) R.string.translate_pause else R.string.translate_resume)
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (listening) p.on else p.surface)
                    .clickable(role = Role.Switch, onClickLabel = pauseLabel, onClick = onPauseToggle)
                    .semantics { contentDescription = pauseLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sym_hearing), contentDescription = null, tint = if (listening) p.screen else p.text2, modifier = Modifier.size(24.dp))
            }
            val hint = stringResource(R.string.translate_my_phrase_hint)
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(p.surface).padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(hint, color = p.text2, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = hasKey,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            if (text.isNotBlank()) {
                GlassIconButton(R.drawable.ic_sym_send, stringResource(R.string.send), onClick = ::send, size = 52.dp, enabled = hasKey, kind = GlassButtonKind.PRIMARY)
            } else {
                GlassIconButton(R.drawable.ic_sym_mic, stringResource(R.string.translate_me_speak), onClick = onMeSpeak, size = 52.dp, enabled = hasKey, kind = GlassButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun LanguageButton(state: TranslateUiState, onLanguage: (PartnerLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.partner_language_cd, stringResource(state.language.titleRes))
    Box {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(LocalSputnikColors.current.surface)
                .clickable(role = Role.Button, onClickLabel = description) { open = true }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Text(state.language.flag, fontSize = 24.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PartnerLanguage.partnersFor(state.my).forEach { language ->
                DropdownMenuItem(
                    leadingIcon = { Text(language.flag, fontSize = 22.sp) },
                    text = { Text(stringResource(language.titleRes), fontWeight = if (language == state.language) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onLanguage(language)
                    },
                )
            }
        }
    }
}

/**
 * Настройки умения «Переводчик»: язык собеседника, чей микрофон его слушает, озвучивать ли мою фразу,
 * обращение и пол в моих фразах. Перевод — на мой язык (язык приложения).
 */
@Composable
fun TranslatorSettingsScreen(
    state: TranslateUiState,
    partnerMic: PartnerMic,
    speakMine: Boolean,
    style: CommunicationStyle,
    onLanguage: (PartnerLanguage) -> Unit,
    onPartnerMic: (PartnerMic) -> Unit,
    onSpeakMine: (Boolean) -> Unit,
    onStyle: (CommunicationStyle) -> Unit,
) {
    val p = LocalSputnikColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            SettingTitle(stringResource(R.string.translator_partner_language))
            var open by remember { mutableStateOf(false) }
            Box {
                GlassButton(state.language.flag + "  " + stringResource(state.language.titleRes) + " ▾", onClick = { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    PartnerLanguage.partnersFor(state.my).forEach { language ->
                        DropdownMenuItem(
                            leadingIcon = { Text(language.flag, fontSize = 22.sp) },
                            text = { Text(stringResource(language.titleRes)) },
                            onClick = {
                                open = false
                                onLanguage(language)
                            },
                        )
                    }
                }
            }
            Text(stringResource(R.string.translator_my_language, stringResource(state.my.titleRes)), style = MaterialTheme.typography.bodySmall, color = p.text2)
            Text(stringResource(R.string.translator_auto_detect_soon), style = MaterialTheme.typography.bodySmall, color = p.text2)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SettingTitle(stringResource(R.string.translator_partner_mic))
            Segments(listOf(PartnerMic.PHONE to R.string.mic_phone, PartnerMic.GLASSES to R.string.mic_glasses), partnerMic, onPartnerMic)
            Text(stringResource(R.string.translator_partner_mic_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onSpeakMine(!speakMine) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.translator_speak_mine), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.translator_speak_mine_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
                }
                Switch(checked = speakMine, onCheckedChange = null)
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            SettingTitle(stringResource(R.string.translator_formality))
            Segments(Formality.entries.map { it to it.title }, style.formality) { onStyle(style.copy(formality = it)) }
            SettingTitle(stringResource(R.string.translator_gender))
            Segments(SpeakerGender.entries.map { it to it.title }, style.gender) { onStyle(style.copy(gender = it)) }
            Text(stringResource(R.string.style_gender_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        }
    }
}

@Composable
private fun SettingTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun <T> Segments(choices: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

/** Языки, где перевод моей фразы зависит от пола: подсказка, если пол не указан. */
fun needsGenderHint(language: PartnerLanguage, style: CommunicationStyle): Boolean =
    language in TranslationPrompt.GENDERED && style.gender == SpeakerGender.UNSET
