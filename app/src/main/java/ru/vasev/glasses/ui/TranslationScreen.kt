package ru.vasev.glasses.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.speech.VoiceStatus
import ru.vasev.glasses.translate.PartnerLanguage
import ru.vasev.glasses.translate.TranslatePhase
import ru.vasev.glasses.translate.TranslateUiState
import ru.vasev.glasses.translate.TranslationViewModel
import ru.vasev.glasses.translate.Who
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassBackgroundText
import ru.vasev.glasses.ui.glass.glassContentSource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.vasev.glasses.profile.SpeakerGender
import ru.vasev.glasses.translate.TranslationPrompt

@Composable
fun TranslationScreen(
    viewModel: TranslationViewModel,
    onPartnerSpeaks: () -> Unit,
    onMeReplies: () -> Unit,
    onInstallVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val busy = state.phase != TranslatePhase.IDLE
    /** Перевод на весь экран крупным текстом (текст, подпись). */
    var large by remember { mutableStateOf<Pair<String, String>?>(null) }
    val styleViewModel: StyleViewModel = viewModel()
    val style by styleViewModel.saved.collectAsStateWithLifecycle()
    val genderHintShown by styleViewModel.genderHintShown.collectAsStateWithLifecycle()
    var styleOpen by rememberSaveable { mutableStateOf(false) }
    if (styleOpen) {
        StyleScreen(styleViewModel) { styleOpen = false }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = LocalTabBarInset.current)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LanguagePicker(state.language, PartnerLanguage.partnersFor(state.my), enabled = !busy, onSelect = viewModel::setLanguage)
                }
                Spacer(Modifier.size(8.dp))
                // Подпись и переключатель — один элемент: TalkBack читает «Непрерывно, переключатель, вкл/выкл».
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .glass(GlassShapes.pill)
                        .toggleable(value = state.continuous, role = Role.Switch, onValueChange = viewModel::setContinuous)
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.continuous), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.size(8.dp))
                    Switch(checked = state.continuous, onCheckedChange = null)
                }
            }

            if (!hasApiKey) MissingKeyNotice(model, onOpenSettings)
            state.voiceHint?.let { (language, status) ->
                val title = stringResource(language.titleRes)
                Hint(stringResource(if (status == VoiceStatus.UNSUPPORTED) R.string.voice_unsupported else R.string.voice_not_downloaded, title)) {
                    GlassButton(
                        stringResource(R.string.download_voice),
                        onClick = onInstallVoice,
                        icon = R.drawable.ic_sym_download,
                        kind = GlassButtonKind.NORMAL,
                    )
                }
            }

            // Язык с родом или вежливыми частицами, а мой пол не указан — один раз подсказать «Стиль общения».
            if (state.language in TranslationPrompt.GENDERED && style.gender == SpeakerGender.UNSET && !genderHintShown) {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Text(stringResource(R.string.translate_gender_hint), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(stringResource(R.string.close), onClick = styleViewModel::markGenderHintShown)
                        GlassButton(
                            stringResource(R.string.translate_gender_hint_open),
                            onClick = {
                                styleViewModel.markGenderHintShown()
                                styleOpen = true
                            },
                            kind = GlassButtonKind.PRIMARY,
                        )
                    }
                }
            }

            // Лента уходит под нижние стеклянные кнопки — они её размывают.
            Feed(
                state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onShowLarge = { text, caption -> large = text to caption },
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                Row(
                    Modifier.fillMaxWidth().glass(GlassShapes.pill, blur = true).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        state.phase.labelRes?.let { stringResource(it) }.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    GlassButton(stringResource(R.string.stop), onClick = viewModel::stop, icon = R.drawable.ic_sym_stop, blur = true)
                }
            } else if (state.feed.isNotEmpty()) {
                GlassButton(
                    stringResource(R.string.clear_feed),
                    onClick = viewModel::clearFeed,
                    icon = R.drawable.ic_sym_clear_all,
                    blur = true,
                    modifier = Modifier.align(Alignment.End),
                )
            }

            // Цвета кнопок совпадают с цветами реплик: собеседник — tertiary, я — primary.
            GlassButton(
                stringResource(R.string.partner_speaks, state.language.flag),
                onClick = onPartnerSpeaks,
                icon = R.drawable.ic_sym_hearing,
                kind = GlassButtonKind.TONAL_TERTIARY,
                enabled = hasApiKey && (!busy || state.phase == TranslatePhase.LISTENING_PARTNER),
                blur = true,
                // Ниже на четверть и на шаг мельче, чем было (72 dp, titleLarge); зона нажатия всё равно ≥ 48 dp.
                minHeight = 54.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassButton(
                stringResource(R.string.i_reply) + " " + state.my.flag,
                onClick = onMeReplies,
                icon = R.drawable.ic_sym_campaign,
                kind = GlassButtonKind.PRIMARY,
                enabled = hasApiKey,
                blur = true,
                // Ниже на четверть и на шаг мельче, чем было (72 dp, titleLarge); зона нажатия всё равно ≥ 48 dp.
                minHeight = 54.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    large?.let { (text, caption) -> FullscreenText(text, caption) { large = null } }
}

@Composable
private fun LanguagePicker(selected: PartnerLanguage, languages: List<PartnerLanguage>, enabled: Boolean, onSelect: (PartnerLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = stringResource(selected.titleRes)
    val description = stringResource(R.string.partner_language_cd, selectedTitle)
    Box {
        GlassButton(
            selected.flag + "  " + selectedTitle + " ▾",
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        )
        // Меню — отдельное окно, размыть под ним приложение нельзя: оно непрозрачное, как в Material 3.
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
            languages.forEach { language ->
                DropdownMenuItem(
                    leadingIcon = { Text(language.flag, fontSize = 22.sp) },
                    text = {
                        Text(
                            stringResource(language.titleRes),
                            fontWeight = if (language == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(language)
                    },
                )
            }
        }
    }
}

@Composable
private fun Feed(state: TranslateUiState, modifier: Modifier, onShowLarge: (String, String) -> Unit) {
    val listState = rememberLazyListState()
    val rows = state.feed.size + (if (state.pendingWho != null) 1 else 0) + (if (state.error != null) 1 else 0)
    LaunchedEffect(rows, state.pending) {
        if (rows > 0) listState.animateScrollToItem(rows - 1)
    }

    if (rows == 0) {
        Box(modifier) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.translate_empty, stringResource(state.my.titleRes)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    val partnerCaption = state.language.flag + "  " + stringResource(state.language.titleRes)
    val myCaption = state.my.flag + "  " + stringResource(state.my.titleRes)
    val unavailable = stringResource(R.string.translation_unavailable)
    LazyColumn(
        modifier = modifier.glassContentSource(),
        state = listState,
        // Снизу — место под кнопки: последнюю реплику можно докрутить над ними.
        contentPadding = PaddingValues(top = 4.dp, bottom = BUTTONS_SPACE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.feed, key = { it.id }) { item ->
            val flag = if (item.line.who == Who.PARTNER) state.language.flag else state.my.flag
            Utterance(
                who = item.line.who,
                flag = flag,
                original = item.line.original,
                translation = item.line.translation ?: unavailable,
                model = item.model,
                // Мой ответ — на языке собеседника: его и показываем ему крупно.
                onShowLarge = item.line.translation?.let { text ->
                    {
                        val caption = if (item.line.who == Who.ME) partnerCaption else myCaption
                        onShowLarge(text, caption)
                    }
                },
            )
        }
        state.pendingWho?.let { who ->
            item(key = "pending") {
                Utterance(who, if (who == Who.PARTNER) state.language.flag else state.my.flag, state.pending.ifEmpty { "…" }, null)
            }
        }
        state.error?.let { error -> item(key = "error") { Hint(error) } }
    }
}

/**
 * Реплика — стеклянный пузырь: оригинал мелко, перевод крупно. Собеседник — слева, стекло цвета tertiary;
 * я — справа, primary. Нажатие на перевод — показать его на весь экран крупным текстом (непрозрачно).
 */
@Composable
private fun Utterance(
    who: Who,
    flag: String,
    original: String,
    translation: String?,
    model: ModelTag? = null,
    onShowLarge: (() -> Unit)? = null,
) {
    val mine = who == Who.ME
    val colors = MaterialTheme.colorScheme
    val container = if (mine) colors.primaryContainer else colors.tertiaryContainer
    val content = if (mine) colors.onPrimaryContainer else colors.onTertiaryContainer
    val shape = if (mine) RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp) else RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .glass(shape, tint = container, text = content)
                .then(
                    if (onShowLarge != null) {
                        Modifier.clickable(onClickLabel = stringResource(R.string.show_translation_full_screen), onClick = onShowLarge)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Оригинал — тем же цветом, что и перевод (контраст 4.5:1), но мельче.
            Text("$flag  $original", style = MaterialTheme.typography.bodySmall, color = content)
            if (translation != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(translation, style = MaterialTheme.typography.titleLarge, color = content, modifier = Modifier.weight(1f, fill = false))
                    if (onShowLarge != null) {
                        Spacer(Modifier.size(8.dp))
                        Icon(
                            painterResource(R.drawable.ic_sym_fullscreen),
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (model != null) {
            Text(
                modelLabel(model),
                style = MaterialTheme.typography.labelSmall,
                color = glassBackgroundText(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String, action: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth(), tint = c.errorContainer, text = c.onErrorContainer, contentPadding = PaddingValues(14.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        action?.let { Box(Modifier.align(Alignment.End)) { it() } }
    }
}

/** Высота нижних кнопок с полосой «Стоп» / «Очистить ленту». */
private val BUTTONS_SPACE = 210.dp
