package ru.vasev.glasses.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import ru.vasev.glasses.R
import ru.vasev.glasses.media.MediaItem
import ru.vasev.glasses.media.MediaLibrary
import ru.vasev.glasses.notes.Note
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.theme.LocalSputnikColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Медиа»: снимки (из чатов и с камер — очки, телефон, галерея) и заметки «Запомни».
 * Снимок можно отправить в чат (прикрепляется к полю ввода), сохранить в галерею или удалить.
 * Видео с очков появятся вместе с задачей «Видео» (BACKLOG).
 */
@Composable
fun MediaScreen(
    library: MediaLibrary,
    viewModel: AssistantEngine,
    onToChat: (MediaItem) -> Unit,
    onRoute: (Note) -> Unit,
) {
    val items by library.items.collectAsState()
    LaunchedEffect(Unit) { library.refresh() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var open by remember { mutableStateOf<MediaItem?>(null) }
    var notePhoto by remember { mutableStateOf<Bitmap?>(null) }
    val p = LocalSputnikColors.current
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            listOf(R.string.media_photos, R.string.notes_title).forEachIndexed { index, label ->
                SegmentedButton(selected = tab == index, onClick = { tab = index }, shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {}) {
                    Text(stringResource(label))
                }
            }
        }
        if (tab == 1) {
            NotesScreen(viewModel, onClose = {}, onOpenPhoto = { notePhoto = it }, onRoute = onRoute, embedded = true)
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.media_empty), color = p.text2, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(minSize = 110.dp),
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.file.path }) { item -> MediaTile(library, item) { open = item } }
            }
        }
    }
    open?.let { item -> MediaViewer(library, item, onToChat = { onToChat(item); open = null }, onClose = { open = null }) }
    notePhoto?.let { FullscreenPhoto(it, null) { notePhoto = null } }
}

@Composable
private fun MediaTile(library: MediaLibrary, item: MediaItem, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item) { value = library.thumbnail(item) }
    val label = stringResource(R.string.media_open_photo, DATE.format(item.takenAt.atZone(ZoneId.systemDefault())))
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(LocalSputnikColors.current.card)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        thumb?.let { Image(it.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Снимок на весь экран: когда и откуда, «В чат», «Сохранить в галерею», «Удалить» (с подтверждением). */
@Composable
private fun MediaViewer(library: MediaLibrary, item: MediaItem, onToChat: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val full by produceState<Bitmap?>(null, item) { value = library.thumbnail(item, side = 1600) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            full?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.photo_full_screen), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)) {
                Text(DATE.format(item.takenAt.atZone(ZoneId.systemDefault())), color = Color.White, fontWeight = FontWeight.SemiBold)
                item.source?.let { Text(stringResource(it.titleRes), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall) }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                GlassButton(stringResource(R.string.media_to_chat), onClick = onToChat, kind = GlassButtonKind.PRIMARY)
                val saved = stringResource(R.string.media_saved)
                val failed = stringResource(R.string.shot_save_failed)
                GlassButton(stringResource(R.string.media_save), onClick = {
                    scope.launch { Toast.makeText(context, if (library.saveToGallery(item)) saved else failed, Toast.LENGTH_SHORT).show() }
                })
                GlassButton(stringResource(R.string.delete), onClick = { confirmDelete = true }, kind = GlassButtonKind.ERROR)
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()) { Text(stringResource(R.string.close), color = Color.White) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.media_delete_title)) },
            text = { Text(stringResource(R.string.media_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch { library.delete(item) }
                        onClose()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalSputnikColors.current.err),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
