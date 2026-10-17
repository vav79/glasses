package ru.vasev.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.translate.PartnerLanguage
import ru.vasev.glasses.translate.TranslatePhase
import ru.vasev.glasses.translate.TranslateUiState
import ru.vasev.glasses.translate.TranslationViewModel
import ru.vasev.glasses.translate.Who

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                LanguagePicker(state.language, enabled = !busy, onSelect = viewModel::setLanguage)
            }
            Spacer(Modifier.size(8.dp))
            // Подпись и переключатель — один элемент: TalkBack читает «Непрерывно, переключатель, вкл/выкл».
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .toggleable(value = state.continuous, role = Role.Switch, onValueChange = viewModel::setContinuous)
                    .padding(start = 8.dp),
            ) {
                Text("Непрерывно", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Spacer(Modifier.size(8.dp))
                Switch(checked = state.continuous, onCheckedChange = null)
            }
        }

        if (!hasApiKey) MissingKeyNotice(model, onOpenSettings)
        state.voiceHint?.let { hint ->
            Hint(hint) {
                TextButton(onClick = onInstallVoice) {
                    Icon(painterResource(R.drawable.ic_sym_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Скачать голос")
                }
            }
        }

        Feed(
            state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onShowLarge = { text, caption -> large = text to caption },
        )

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(12.dp))
                Text(state.phase.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = viewModel::stop) {
                    Icon(painterResource(R.drawable.ic_sym_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Стоп")
                }
            }
        } else if (state.feed.isNotEmpty()) {
            TextButton(onClick = viewModel::clearFeed, modifier = Modifier.align(Alignment.End)) {
                Icon(painterResource(R.drawable.ic_sym_clear_all), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Очистить ленту")
            }
        }

        // Цвета кнопок совпадают с цветами реплик: собеседник — tertiary, я — primary.
        Button(
            onClick = onPartnerSpeaks,
            enabled = hasApiKey && (!busy || state.phase == TranslatePhase.LISTENING_PARTNER),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Icon(painterResource(R.drawable.ic_sym_hearing), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text("Собеседник говорит ${state.language.flag}", fontSize = 20.sp)
        }

        Button(
            onClick = onMeReplies,
            enabled = hasApiKey,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Icon(painterResource(R.drawable.ic_sym_campaign), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Text("Я отвечаю", fontSize = 20.sp)
        }
    }

    large?.let { (text, caption) -> FullscreenText(text, caption) { large = null } }
}

@Composable
private fun LanguagePicker(selected: PartnerLanguage, enabled: Boolean, onSelect: (PartnerLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Язык собеседника: ${selected.title}. Изменить" },
        ) {
            Text("${selected.flag}  ${selected.title} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PartnerLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    leadingIcon = { Text(language.flag, fontSize = 22.sp) },
                    text = {
                        Text(
                            language.title,
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
        Text(
            "Выберите язык собеседника. «Собеседник говорит» — перевод его речи на русский для вас. " +
                "«Я отвечаю» — перевод вашей речи, его услышит собеседник из динамика телефона. " +
                "В непрерывном режиме приложение снова слушает собеседника, пока вы не нажмёте «Стоп».",
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(modifier = modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.feed, key = { it.id }) { item ->
            val flag = if (item.line.who == Who.PARTNER) state.language.flag else "🇷🇺"
            Utterance(
                who = item.line.who,
                flag = flag,
                original = item.line.original,
                translation = item.line.translation ?: "перевод недоступен",
                model = item.model,
                // Мой ответ — на языке собеседника: его и показываем ему крупно.
                onShowLarge = item.line.translation?.let { text ->
                    {
                        val caption = if (item.line.who == Who.ME) "${state.language.flag}  ${state.language.title}" else "🇷🇺  Русский"
                        onShowLarge(text, caption)
                    }
                },
            )
        }
        state.pendingWho?.let { who ->
            item(key = "pending") {
                Utterance(who, if (who == Who.PARTNER) state.language.flag else "🇷🇺", state.pending.ifEmpty { "…" }, null)
            }
        }
        state.error?.let { error -> item(key = "error") { Hint(error) } }
    }
}

/**
 * Реплика: оригинал мелко, перевод крупно. Собеседник — слева, цвет tertiary; я — справа, цвет primary.
 * Нажатие на перевод — показать его на весь экран крупным текстом.
 */
@Composable
private fun Utterance(
    who: Who,
    flag: String,
    original: String,
    translation: String?,
    model: String = "",
    onShowLarge: (() -> Unit)? = null,
) {
    val mine = who == Who.ME
    val colors = MaterialTheme.colorScheme
    val container = if (mine) colors.primaryContainer else colors.tertiaryContainer
    val content = if (mine) colors.onPrimaryContainer else colors.onTertiaryContainer
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .background(container)
                .then(
                    if (onShowLarge != null) {
                        Modifier.clickable(onClickLabel = "Показать перевод на весь экран", onClick = onShowLarge)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("$flag  $original", style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.75f))
            if (translation != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(translation, style = MaterialTheme.typography.titleLarge, color = content, modifier = Modifier.weight(1f, fill = false))
                    if (onShowLarge != null) {
                        Spacer(Modifier.size(8.dp))
                        Icon(
                            painterResource(R.drawable.ic_sym_fullscreen),
                            contentDescription = null,
                            tint = content.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (model.isNotEmpty()) {
            Text(
                model,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String, action: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.let { Box(Modifier.align(Alignment.End)) { it() } }
        }
    }
}
