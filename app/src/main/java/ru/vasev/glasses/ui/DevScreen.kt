package ru.vasev.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassContentSource
import ru.vasev.glasses.llm.JournalEntry
import ru.vasev.glasses.llm.RequestJournal
import ru.vasev.glasses.llm.Role
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Вкладка «Разработка»: пока один пункт — журнал запросов к моделям с экспортом в JSONL. */
@Composable
fun DevScreen(entries: StateFlow<List<JournalEntry>>, onExport: () -> Unit, onClear: () -> Unit) {
    val list by entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val total = list.sumOf { it.costUsd ?: 0.0 }

    Column(
        Modifier.fillMaxSize().padding(top = LocalTabBarInset.current).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.dev_journal_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dev_journal_summary, list.size, usd(total)), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dev_journal_hint, RequestJournal.MAX_ENTRIES),
                style = MaterialTheme.typography.bodySmall,
                color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        if (confirmClear) {
            Text(stringResource(R.string.dev_clear_confirm), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(stringResource(R.string.dev_clear), onClick = { confirmClear = false; onClear() }, kind = GlassButtonKind.ERROR)
                GlassButton(stringResource(R.string.cancel), onClick = { confirmClear = false })
            }
        } else {
            // При крупном шрифте кнопки не помещаются в строку — вторая переносится целиком, а не по слогам.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(stringResource(R.string.dev_export), onClick = onExport, icon = R.drawable.ic_sym_share, kind = GlassButtonKind.PRIMARY, enabled = list.isNotEmpty())
                GlassButton(stringResource(R.string.dev_clear), onClick = { confirmClear = true }, icon = R.drawable.ic_sym_delete, enabled = list.isNotEmpty())
            }
        }

        if (list.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dev_journal_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            // Новые сверху.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().glassContentSource(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.asReversed(), key = { "${it.time}-${it.model}-${it.status}" }) { EntryCard(it) }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: JournalEntry) {
    val failed = entry.status.startsWith("error")
    val colors = MaterialTheme.colorScheme
    GlassCard(
        Modifier.fillMaxWidth(),
        tint = if (failed) colors.errorContainer else colors.surface,
        text = if (failed) colors.onErrorContainer else colors.onSurface,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val mode = stringResource(if (entry.role == Role.ASSISTANT) R.string.tab_assistant else R.string.tab_translate)
                Text(
                    mode + if (entry.fallback) " · " + stringResource(R.string.dev_fallback) else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(TIME.format(entry.time.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelMedium)
            }
            Text("${entry.provider.title} · ${entry.model} · ${entry.status}", style = MaterialTheme.typography.bodyMedium)
            val u = entry.usage
            val cache = if (u.cacheRead > 0 || u.cacheWrite > 0) " · cache ${u.cacheRead}/${u.cacheWrite}" else ""
            Text(
                "${entry.latencyMs} ms · in ${u.input} / out ${u.output}$cache · ≈ ${entry.costUsd?.let(::usd) ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            val voice = listOfNotNull(
                entry.firstSoundMs?.let { stringResource(R.string.dev_first_sound, it.toInt()) },
                if (entry.interrupted) stringResource(R.string.answer_interrupted) else null,
            )
            if (voice.isNotEmpty()) Text(voice.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            entry.question?.let { Text("> $it", style = MaterialTheme.typography.bodySmall) }
            entry.answer?.let { Text("< $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Доллары: мелкие суммы — с пятью знаками, чтобы стоимость одного запроса не превращалась в ноль. */
private fun usd(value: Double): String =
    if (value < 0.01) String.format(Locale.US, "$%.5f", value) else String.format(Locale.US, "$%.4f", value)

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
