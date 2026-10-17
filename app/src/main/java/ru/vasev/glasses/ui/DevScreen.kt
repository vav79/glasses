package ru.vasev.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.dev_journal_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.dev_journal_summary, list.size, usd(total)), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.dev_journal_hint, RequestJournal.MAX_ENTRIES),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (confirmClear) {
            Text(stringResource(R.string.dev_clear_confirm), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.dev_clear)) }
                OutlinedButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            // При крупном шрифте кнопки не помещаются в строку — вторая переносится целиком, а не по слогам.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, enabled = list.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_sym_share), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dev_export), maxLines = 1)
                }
                OutlinedButton(onClick = { confirmClear = true }, enabled = list.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_sym_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dev_clear), maxLines = 1)
                }
            }
        }

        if (list.isEmpty()) {
            Text(
                stringResource(R.string.dev_journal_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Новые сверху.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.asReversed(), key = { "${it.time}-${it.model}-${it.status}" }) { EntryCard(it) }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: JournalEntry) {
    val failed = entry.status.startsWith("error")
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) colors.errorContainer else colors.surfaceContainer,
            contentColor = if (failed) colors.onErrorContainer else colors.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            entry.question?.let { Text("> $it", style = MaterialTheme.typography.bodySmall) }
            entry.answer?.let { Text("< $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Доллары: мелкие суммы — с пятью знаками, чтобы стоимость одного запроса не превращалась в ноль. */
private fun usd(value: Double): String =
    if (value < 0.01) String.format(Locale.US, "$%.5f", value) else String.format(Locale.US, "$%.4f", value)

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
