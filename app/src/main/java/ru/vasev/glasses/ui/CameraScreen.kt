package ru.vasev.glasses.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.PhoneCamera
import ru.vasev.glasses.camera.Shot
import ru.vasev.glasses.speech.MicChoice
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassBackgroundText
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «Камера» — работа со снимками и устройствами: строка источников, большой видоискатель (или последний кадр
 * с очков, или выбранный снимок из ленты), лента последних снимков, затвор «Что я вижу» (нажатие — описать,
 * удержание — свой вопрос голосом), «Переведи» и «Запомни». Ответ — шторкой поверх снимка и в общей истории.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    cameraGranted: Boolean,
    onSelectSource: (ImageSource) -> Unit,
    onConnectGlasses: () -> Unit,
    onDescribe: () -> Unit,
    onAsk: () -> Unit,
    onTranslate: () -> Unit,
    onRemember: () -> Unit,
    onFollowUp: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val source by viewModel.imageSource.collectAsStateWithLifecycle()
    val status by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val battery by viewModel.glassesBattery.collectAsStateWithLifecycle()
    val mic by viewModel.micChoice.collectAsStateWithLifecycle()
    val shots by viewModel.shots.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.phase != Phase.IDLE

    Column(
        Modifier.fillMaxSize().padding(top = LocalTabBarInset.current).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Источники — одной строкой чипов, выбор — шторкой снизу.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Чипы сжимаются (с многоточием), статус очков справа — всегда целиком.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(R.drawable.ic_sym_photo_camera, stringResource(source.titleRes), stringResource(R.string.photos_source_title), enabled = !busy, Modifier.weight(1f, fill = false)) { sheet = SHEET_SOURCE }
                Chip(R.drawable.ic_sym_mic, stringResource(mic.titleRes), stringResource(R.string.mic_label), enabled = !busy, Modifier.weight(1f, fill = false)) { sheet = SHEET_MIC }
            }
            if (status.ready) {
                GlassesState(battery)
            } else {
                GlassButton(
                    stringResource(R.string.glasses_connect_short),
                    onClick = onConnectGlasses,
                    kind = GlassButtonKind.PRIMARY,
                    minHeight = 44.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // Видоискатель — крупно; шторка ответа ложится поверх снимка.
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            Viewfinder(viewModel, state, source, status.ready, shots, cameraGranted, onSelectSource)
            ResultSheet(viewModel, state, onFollowUp, Modifier.align(Alignment.BottomCenter))
        }

        ShotStrip(viewModel, shots, state.selectedShot, enabled = !busy)

        Text(
            stringResource(if (state.selectedShot != null) R.string.camera_hint_shot else R.string.camera_hint) +
                (state.phase.labelRes?.let { " · " + stringResource(it) } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            color = glassBackgroundText(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            LabeledAction(R.drawable.ic_sym_translate, stringResource(R.string.camera_translate), enabled = !busy && hasApiKey, onClick = onTranslate)
            Shutter(
                phase = state.phase,
                enabled = busy || hasApiKey,
                onTap = if (busy && !state.phase.interruptible) viewModel::stop else onDescribe,
                onHold = onAsk,
            )
            LabeledAction(R.drawable.ic_sym_bookmark, stringResource(R.string.camera_remember), enabled = !busy, onClick = onRemember)
        }
    }

    when (sheet) {
        SHEET_SOURCE -> ChoiceSheet(
            title = stringResource(R.string.photos_source_title),
            options = viewModel.availableSources.map { Triple(it.name, stringResource(it.titleRes), true) },
            selected = source.name,
            note = null,
            onPick = { name -> ImageSource.entries.firstOrNull { it.name == name }?.let(onSelectSource) },
            onDismiss = { sheet = null },
        )
        SHEET_MIC -> ChoiceSheet(
            title = stringResource(R.string.mic_label),
            options = MicChoice.entries.map { choice ->
                val title = stringResource(choice.titleRes)
                Triple(choice.name, if (choice.available) title else title + " · " + stringResource(R.string.mic_glasses_soon), choice.available)
            },
            selected = mic.name,
            note = stringResource(R.string.mic_auto_hint),
            onPick = { name -> MicChoice.entries.firstOrNull { it.name == name }?.let(viewModel::setMicChoice) },
            onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun Chip(@DrawableRes icon: Int, value: String, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .glass(GlassShapes.pill)
            .clip(GlassShapes.pill)
            .combinedClickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(painterResource(R.drawable.ic_sym_expand_more), contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/** «● Очки · 76 %» — подключены; заряд, если очки его сообщили. */
@Composable
fun GlassesState(battery: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(glassAccent(accent = Color(0xFF2E9D57))))
        Text(
            battery?.let { stringResource(R.string.glasses_on_battery, it) } ?: stringResource(R.string.glasses_on),
            style = MaterialTheme.typography.labelLarge,
            color = glassBackgroundText(),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun Viewfinder(
    viewModel: MainViewModel,
    state: UiState,
    source: ImageSource,
    glassesReady: Boolean,
    shots: List<Shot>,
    cameraGranted: Boolean,
    onSelectSource: (ImageSource) -> Unit,
) {
    val selected = state.selectedShotPreview
    val label: String
    when {
        selected != null -> {
            label = stringResource(R.string.frame_shot)
            Image(selected.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            GlassButton(
                stringResource(R.string.back_to_live),
                onClick = { viewModel.selectShot(null) },
                modifier = Modifier.padding(10.dp),
                minHeight = 44.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                textStyle = MaterialTheme.typography.labelMedium,
            )
        }
        source == ImageSource.PHONE_CAMERA -> {
            label = stringResource(R.string.frame_phone)
            if (cameraGranted) {
                PhoneViewfinderFull(viewModel.phoneCamera)
            } else {
                Placeholder(stringResource(R.string.no_camera_access)) {
                    GlassButton(stringResource(R.string.allow), onClick = { onSelectSource(ImageSource.PHONE_CAMERA) }, kind = GlassButtonKind.PRIMARY)
                }
            }
        }
        else -> {
            label = stringResource(R.string.frame_glasses_last)
            // Последний кадр с очков (или симулятора) из ленты.
            val last = shots.firstOrNull { it.source.usesGlasses }
            val frame by produceState<Bitmap?>(null, last?.id) { value = last?.let { viewModel.shotImage(it) } }
            when {
                frame != null -> Image(frame!!.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                !glassesReady -> Placeholder(stringResource(R.string.frame_glasses_off)) {}
                else -> Placeholder(stringResource(R.string.frame_glasses_empty)) {}
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.clip(GlassShapes.pill).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Placeholder(text: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        action()
    }
}

/** Видоискатель на всю карточку: камера работает, только пока он на экране. */
@Composable
private fun PhoneViewfinderFull(camera: PhoneCamera) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        camera.bind(lifecycleOwner, previewView)
        onDispose { camera.unbind() }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Шторка ответа поверх снимка: суть, «Подробнее», ссылки и заметки, «Спросить ещё». */
@Composable
private fun ResultSheet(viewModel: MainViewModel, state: UiState, onFollowUp: () -> Unit, modifier: Modifier) {
    val item = state.chat.lastOrNull { it.id == state.cameraSheetId }
    val pending = state.cameraTurn && (state.pendingAnswer.isNotEmpty() || state.pendingQuestion.isNotEmpty())
    if (item == null && !pending) return
    val c = MaterialTheme.colorScheme
    var expanded by rememberSaveable(item?.id) { mutableStateOf(item?.translation == true) }
    val summary = if (pending) state.pendingAnswer.ifEmpty { state.pendingQuestion.ifEmpty { "…" } } else item!!.answer
    val details = if (pending) state.pendingDetails else item!!.details
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            // Непрозрачная шторка: ответ читается поверх любого снимка.
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(c.surfaceContainerHigh.copy(alpha = 0.96f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.outlineVariant))
        if (pending && state.pendingQuestion.isNotEmpty() && state.pendingAnswer.isNotEmpty()) {
            Text(state.pendingQuestion, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
        } else if (item != null) {
            Text(item.question, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
        }
        Text(summary, style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
        if (expanded && details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        item?.links?.forEach { link -> LinkChip(link) { viewModel.requestLink(link) } }
        item?.notes?.forEach { card -> Text(card.note.text.ifEmpty { stringResource(R.string.note_no_text) }, style = MaterialTheme.typography.bodyMedium, color = c.onSurface) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (details.isNotEmpty()) {
                GlassButton(
                    stringResource(if (expanded) R.string.answer_less else R.string.answer_more),
                    onClick = { expanded = !expanded },
                    minHeight = 44.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    textStyle = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.weight(1f))
            GlassIconButton(R.drawable.ic_sym_close, stringResource(R.string.camera_close_sheet), onClick = viewModel::closeCameraSheet, size = 44.dp)
            GlassIconButton(
                R.drawable.ic_sym_mic,
                stringResource(R.string.camera_ask_more),
                onClick = onFollowUp,
                size = 48.dp,
                kind = GlassButtonKind.PRIMARY,
                enabled = state.phase == Phase.IDLE || state.phase.interruptible,
            )
        }
    }
}

/** Лента: первая плитка — галерея, дальше последние снимки; долгое нажатие — удалить или сохранить в галерею. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotStrip(viewModel: MainViewModel, shots: List<Shot>, selected: Shot?, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.shot_saved)
    val failedText = stringResource(R.string.shot_save_failed)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { selectableGroup() }) {
        item(key = "gallery") {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .glass(RoundedCornerShape(14.dp), elevation = 0.dp)
                    .combinedClickable(enabled = enabled, role = Role.Button, onClick = viewModel::pickFromGallery)
                    .semantics { contentDescription = context.getString(R.string.gallery_tile) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_sym_photo_library), contentDescription = null)
            }
        }
        items(shots, key = { it.id }) { shot ->
            var menu by remember { mutableStateOf(false) }
            val thumb by produceState<Bitmap?>(null, shot.id) { value = viewModel.shotThumbnail(shot) }
            val isSelected = shot.id == selected?.id
            val label = stringResource(R.string.shot_label, SHOT_TIME.format(shot.takenAt.atZone(ZoneId.systemDefault())))
            Box {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(if (isSelected) 3.dp else 0.dp, glassAccent(), RoundedCornerShape(14.dp))
                        .combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClickLabel = label,
                            onLongClickLabel = stringResource(R.string.shot_delete) + " / " + stringResource(R.string.shot_save),
                            onLongClick = { menu = true },
                            onClick = { viewModel.selectShot(shot) },
                        )
                        .semantics { contentDescription = label },
                ) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shot_save)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sym_download), contentDescription = null) },
                        onClick = {
                            menu = false
                            scope.launch {
                                val ok = viewModel.saveShotToGallery(shot)
                                Toast.makeText(context, if (ok) savedText else failedText, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shot_delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sym_delete), contentDescription = null) },
                        onClick = {
                            menu = false
                            viewModel.deleteShot(shot)
                        },
                    )
                }
            }
        }
    }
}

/** Затвор «Что я вижу»: нажатие — описать, удержание — снимок и свой вопрос голосом. */
@Composable
private fun Shutter(phase: Phase, enabled: Boolean, onTap: () -> Unit, onHold: () -> Unit, diameter: Dp = 96.dp) {
    val c = MaterialTheme.colorScheme
    val label = stringResource(R.string.camera_shutter_label)
    val holdLabel = stringResource(R.string.camera_hint)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(diameter)
                .glass(GlassShapes.pill, tint = c.primaryContainer, text = c.onPrimaryContainer, blur = true, elevation = 12.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .pointerInput(enabled) {
                    if (enabled) detectTapGestures(onTap = { onTap() }, onLongPress = { onHold() })
                }
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick(label) { onTap(); true }
                    customActions = listOf(CustomAccessibilityAction(holdLabel) { onHold(); true })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (phase == Phase.IDLE) R.drawable.ic_sym_visibility else if (phase.interruptible) R.drawable.ic_sym_volume_up else R.drawable.ic_sym_stop),
                contentDescription = null,
                tint = c.onPrimaryContainer,
                modifier = Modifier.size(diameter * 0.42f),
            )
        }
        Text(stringResource(R.string.what_do_i_see), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.onSurface)
    }
}

@Composable
private fun LabeledAction(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(88.dp)) {
        GlassIconButton(icon, label, onClick, size = 56.dp, enabled = enabled, blur = true)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Выбор из списка шторкой снизу (источник снимков, микрофон). Недоступные пункты видны, но не выбираются. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Triple<String, String, Boolean>>,
    selected: String,
    note: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).semantics { selectableGroup() }) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            options.forEach { (key, text, available) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(enabled = available, role = Role.RadioButton) {
                            onPick(key)
                            onDismiss()
                        }
                        .alpha(if (available) 1f else 0.5f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = key == selected, onClick = null, enabled = available)
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

private const val SHEET_SOURCE = "source"
private const val SHEET_MIC = "mic"
private val SHOT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
