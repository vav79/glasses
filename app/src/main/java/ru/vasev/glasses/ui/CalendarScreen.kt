package ru.vasev.glasses.ui

import android.content.Context
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.theme.LocalSputnikColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Событие календаря телефона для списка «Ближайшие». */
data class PhoneEvent(val title: String, val begin: Long, val allDay: Boolean, val location: String?)

/**
 * «Календарь»: события пишутся в календарь телефона. Добавить — кнопкой или из чата («добавь в календарь…»,
 * окно с заполненными полями, сохраняете вы). Ближайшие события — только с разрешением на чтение календаря.
 */
@Composable
fun CalendarScreen(canRead: Boolean, onRequestRead: () -> Unit, onAdd: () -> Unit, onOpenCalendar: () -> Unit) {
    val p = LocalSputnikColors.current
    val context = LocalContext.current
    val events by produceState<List<PhoneEvent>?>(null, canRead) { value = if (canRead) upcomingEvents(context) else null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.calendar_hint), style = MaterialTheme.typography.bodyMedium, color = p.text2)
        GlassButton(stringResource(R.string.calendar_add), onClick = onAdd, icon = R.drawable.ic_sym_calendar_month, kind = GlassButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
        GlassButton(stringResource(R.string.calendar_open), onClick = onOpenCalendar, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.calendar_upcoming), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        when {
            !canRead -> {
                Text(stringResource(R.string.calendar_read_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
                GlassButton(stringResource(R.string.calendar_show_mine), onClick = onRequestRead, modifier = Modifier.fillMaxWidth())
            }
            events == null -> Unit
            events!!.isEmpty() -> Text(stringResource(R.string.calendar_none), color = p.text2)
            else -> events!!.forEach { event ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val zone = ZoneId.systemDefault()
                    val whenText = if (event.allDay) {
                        DAY.format(Instant.ofEpochMilli(event.begin).atZone(java.time.ZoneOffset.UTC))
                    } else {
                        TIME.format(Instant.ofEpochMilli(event.begin).atZone(zone))
                    }
                    Text(listOfNotNull(whenText, event.location).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = p.text2)
                }
            }
        }
    }
}

/** События на ближайшие [DAYS] дней из всех календарей телефона (Instances — с повторами). */
private suspend fun upcomingEvents(context: Context): List<PhoneEvent> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        android.content.ContentUris.appendId(it, now)
        android.content.ContentUris.appendId(it, now + DAYS * 24 * 60 * 60 * 1000L)
    }.build()
    val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
    runCatching {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < MAX_EVENTS) {
                    add(PhoneEvent(c.getString(0).orEmpty(), c.getLong(1), c.getInt(2) == 1, c.getString(3)?.takeIf { it.isNotBlank() }))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

private const val DAYS = 14
private const val MAX_EVENTS = 30
private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
