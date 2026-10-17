package ru.vasev.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            Text("Непрерывно", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Spacer(Modifier.size(8.dp))
            Switch(checked = state.continuous, onCheckedChange = viewModel::setContinuous)
        }

        if (!hasApiKey) MissingKeyNotice(model, onOpenSettings)
        state.voiceHint?.let { hint ->
            Hint(hint) { TextButton(onClick = onInstallVoice) { Text("Скачать голос") } }
        }

        Feed(state, modifier = Modifier.weight(1f).fillMaxWidth())

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(state.phase.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = viewModel::stop) { Text("Стоп") }
            }
        } else if (state.feed.isNotEmpty()) {
            TextButton(onClick = viewModel::clearFeed, modifier = Modifier.align(Alignment.End)) { Text("Очистить ленту") }
        }

        Button(
            onClick = onPartnerSpeaks,
            enabled = hasApiKey && (!busy || state.phase == TranslatePhase.LISTENING_PARTNER),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) { Text("Собеседник говорит", fontSize = 20.sp) }

        FilledTonalButton(
            onClick = onMeReplies,
            enabled = hasApiKey,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) { Text("Я отвечаю", fontSize = 20.sp) }
    }
}

@Composable
private fun LanguagePicker(selected: PartnerLanguage, enabled: Boolean, onSelect: (PartnerLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("${selected.title} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PartnerLanguage.entries.forEach { language ->
                DropdownMenuItem(
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
private fun Feed(state: TranslateUiState, modifier: Modifier) {
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
            Utterance(item.line.who, item.line.original, item.line.translation ?: "перевод недоступен", item.model)
        }
        state.pendingWho?.let { who ->
            item(key = "pending") { Utterance(who, state.pending.ifEmpty { "…" }, translation = null) }
        }
        state.error?.let { error -> item(key = "error") { Hint(error) } }
    }
}

/** Реплика: оригинал мелко, перевод крупно. Собеседник — слева, я — справа. */
@Composable
private fun Utterance(who: Who, original: String, translation: String?, model: String = "") {
    val mine = who == Who.ME
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    if (mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                )
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val content = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                original,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.7f),
            )
            if (translation != null) {
                Text(translation, style = MaterialTheme.typography.titleLarge, color = content)
            }
            if (model.isNotEmpty()) {
                Text(model, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.6f))
            }
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
