package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.notes.Note
import ru.vasev.glasses.notes.NoteSearch
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glassContentSource

/**
 * «Заметки» — поверх вкладки «Ассистент», закрывается кнопкой или «Назад»: список от новых к старым,
 * поиск (тот же, что у модели, — без учёта окончаний) и удаление вместе со снимком.
 */
@Composable
fun NotesScreen(viewModel: MainViewModel, onClose: () -> Unit, onOpenPhoto: (Bitmap) -> Unit, onRoute: (Note) -> Unit) {
    BackHandler(onBack = onClose)
    val all by viewModel.notes.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val shown = remember(all, query) { if (query.isBlank()) all else NoteSearch.search(all, query, all.size) }

    // Непрозрачная подложка: под заметками не должен просвечивать чат.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxSize().padding(top = LocalTabBarInset.current).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.notes_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                )
                GlassIconButton(R.drawable.ic_sym_close, stringResource(R.string.notes_close), onClick = onClose, size = 48.dp)
            }
            if (all.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_search)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_sym_search), contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = GlassShapes.pill,
                )
            }
            when {
                all.isEmpty() -> GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.notes_empty), style = MaterialTheme.typography.bodyLarge)
                }
                shown.isEmpty() -> GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.notes_nothing_found), style = MaterialTheme.typography.bodyLarge)
                }
                else -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().glassContentSource(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { note ->
                        val photo by produceState<Bitmap?>(null, note.id) { value = viewModel.notePhoto(note, NOTE_THUMB_SIDE) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NoteBody(
                                note = note,
                                photo = photo,
                                title = null,
                                onOpenPhoto = { onOpenPhoto(it) },
                                onRoute = onRoute,
                                trailing = {
                                    GlassIconButton(R.drawable.ic_sym_delete, stringResource(R.string.note_delete), onClick = { confirmDelete = note.id }, size = 48.dp)
                                },
                            )
                            if (confirmDelete == note.id) {
                                GlassCard(
                                    Modifier.fillMaxWidth(),
                                    tint = MaterialTheme.colorScheme.errorContainer,
                                    text = MaterialTheme.colorScheme.onErrorContainer,
                                    contentPadding = PaddingValues(12.dp),
                                ) {
                                    Text(stringResource(R.string.note_delete_confirm), style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GlassButton(
                                            stringResource(R.string.note_delete),
                                            onClick = {
                                                confirmDelete = null
                                                viewModel.deleteNote(note.id)
                                            },
                                            kind = GlassButtonKind.ERROR,
                                        )
                                        GlassButton(stringResource(R.string.cancel), onClick = { confirmDelete = null })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val NOTE_THUMB_SIDE = 640
