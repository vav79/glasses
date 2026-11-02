package ru.vasev.glasses.ui

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.theme.LocalSputnikColors
import ru.vasev.glasses.video.RecPhase
import ru.vasev.glasses.video.RecordingRules
import ru.vasev.glasses.video.RecordingService
import ru.vasev.glasses.video.StopReason
import ru.vasev.glasses.video.VideoAudio
import ru.vasev.glasses.video.VideoItem
import ru.vasev.glasses.video.VideoLibrary
import ru.vasev.glasses.video.VideoRecorder
import ru.vasev.glasses.video.VideoSettings
import ru.vasev.glasses.video.VideoSize
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Медиа» → «Видео»: запись с камеры очков (кнопка, время, размер, заряд очков и телефона, свободное место,
 * размер кадра и звук) и список роликов из галереи (нажатие — открыть в плеере, корзина — удалить).
 */
@Composable
fun VideoTab(
    recorder: VideoRecorder,
    settings: VideoSettings,
    library: VideoLibrary,
    glassesBattery: Int?,
    onStart: () -> Unit,
) {
    val p = LocalSputnikColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by recorder.status.collectAsState()
    val size by settings.size.collectAsState()
    val audio by settings.audio.collectAsState()
    val items by library.items.collectAsState()
    var toDelete by remember { mutableStateOf<VideoItem?>(null) }
    LaunchedEffect(Unit) { library.refresh() }
    // Заряд телефона и свободное место — раз в 5 с (запись идёт часами, точнее не нужно).
    val phoneBattery by produceState(0) {
        val battery = context.getSystemService(BatteryManager::class.java)
        while (true) {
            value = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            delay(5_000)
        }
    }
    val free by produceState(Long.MAX_VALUE) {
        while (true) {
            value = recorder.freeBytes()
            delay(5_000)
        }
    }
    val active = status.phase != RecPhase.IDLE

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                val line = when (status.phase) {
                    RecPhase.IDLE -> if (status.segments > 0) {
                        stringResource(R.string.video_status_done, RecordingService.formatDuration(status.elapsedMs), RecordingService.formatBytes(status.bytes), status.segments)
                    } else {
                        stringResource(R.string.video_status_idle)
                    }
                    RecPhase.STARTING -> stringResource(R.string.video_status_starting)
                    RecPhase.RECORDING -> stringResource(R.string.video_status_recording, RecordingService.formatDuration(status.elapsedMs), RecordingService.formatBytes(status.bytes))
                    RecPhase.PAUSED -> stringResource(R.string.video_status_paused)
                    RecPhase.WAITING -> stringResource(R.string.video_status_waiting, status.reason?.let { stringResource(reasonShort(it)) }.orEmpty())
                }
                Text(line, style = if (status.phase == RecPhase.RECORDING) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (status.phase == RecPhase.RECORDING) FontWeight.SemiBold else null)
                Text(
                    stringResource(R.string.video_info, glassesBattery?.let { "$it %" } ?: stringResource(R.string.video_battery_unknown), phoneBattery, RecordingService.formatBytes(free)),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.text2,
                )
                status.audio?.takeIf { active }?.let { Text(stringResource(R.string.video_audio_from, it), style = MaterialTheme.typography.bodySmall, color = p.text2) }
                if (free < RecordingRules.WARN_FREE_BYTES) Text(stringResource(R.string.video_low_storage), style = MaterialTheme.typography.bodySmall, color = p.err)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active) {
                        GlassButton(stringResource(R.string.video_stop), onClick = recorder::stop, kind = GlassButtonKind.ERROR)
                        if (status.phase == RecPhase.PAUSED) GlassButton(stringResource(R.string.video_resume), onClick = recorder::resume)
                    } else {
                        GlassButton(stringResource(R.string.video_start), onClick = onStart, kind = GlassButtonKind.PRIMARY)
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.video_size_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Segments(VideoSize.entries, size, enabled = !active, label = { it.labelRes }, onSelect = settings::setSize)
                Text(stringResource(R.string.video_audio_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Segments(VideoAudio.entries, audio, enabled = !active, label = { it.labelRes }, onSelect = settings::setAudio)
                Text(stringResource(R.string.video_audio_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
            }
        }
        item {
            Text(stringResource(R.string.video_list_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        }
        if (items.isEmpty()) {
            item { Text(stringResource(R.string.video_empty), color = p.text2) }
        }
        items(items, key = { it.uri.toString() }) { video ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                    val open = Intent(Intent.ACTION_VIEW).setDataAndType(video.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(open) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.video_item,
                        DATE.format(video.addedAt.atZone(ZoneId.systemDefault())),
                        RecordingService.formatDuration(video.durationMs),
                        RecordingService.formatBytes(video.sizeBytes),
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { toDelete = video }) {
                    Icon(painterResource(R.drawable.ic_sym_delete), contentDescription = stringResource(R.string.video_delete), tint = p.text2)
                }
            }
        }
        item { Text(" ", modifier = Modifier.padding(bottom = 24.dp)) }
    }
    toDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.video_delete_title)) },
            text = { Text(stringResource(R.string.video_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { library.delete(video) }
                }) { Text(stringResource(R.string.video_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun <T> Segments(values: List<T>, selected: T, enabled: Boolean, label: (T) -> Int, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        values.forEachIndexed { index, value ->
            SegmentedButton(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, values.size),
                icon = {},
            ) { Text(stringResource(label(value)), maxLines = 1) }
        }
    }
}

/** Причина ожидания — коротко для строки состояния (полная фраза звучит голосом). */
private fun reasonShort(reason: StopReason): Int = when (reason) {
    StopReason.HINGE_CLOSED -> R.string.video_reason_hinge
    StopReason.THERMAL -> R.string.video_reason_thermal
    StopReason.PEAK_POWER -> R.string.video_reason_peak
    StopReason.LINK_LOST -> R.string.video_reason_link
    StopReason.SESSION_FAILED -> R.string.video_reason_session
    else -> R.string.video_reason_stream
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
