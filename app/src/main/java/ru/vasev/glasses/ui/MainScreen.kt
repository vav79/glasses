package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.PhoneCamera
import ru.vasev.glasses.glasses.GlassesStatus
import ru.vasev.glasses.llm.ModelTag

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onAsk: () -> Unit,
    onWhatDoISee: () -> Unit,
    onConnectGlasses: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSource: (ImageSource) -> Unit,
    cameraGranted: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val source by viewModel.imageSource.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val busy = state.phase != Phase.IDLE
    var photo by remember { mutableStateOf<Pair<Bitmap, ImageSource?>?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.assistant_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )

            SourceCard(
                source = source,
                sources = viewModel.availableSources,
                status = status,
                camera = viewModel.phoneCamera,
                cameraGranted = cameraGranted,
                enabled = !busy,
                onSelect = onSelectSource,
                onConnectGlasses = onConnectGlasses,
            )

            if (!hasApiKey) MissingKeyNotice(model, onOpenSettings)

            Chat(state, modifier = Modifier.weight(1f).fillMaxWidth(), onOpenPhoto = { bitmap, label -> photo = bitmap to label })

            // Управление: «Что я вижу» — большая кнопка микрофона — «Новый разговор».
            // Пока идёт ответ, кнопка микрофона перебивает его и сразу слушает новый вопрос;
            // пока снимаем или слушаем — останавливает. Остальной экран (фото, «Подробнее») работает как обычно.
            val phaseLabel = state.phase.labelRes?.let { stringResource(it) }.orEmpty()
            Text(
                when {
                    state.phase.interruptible -> stringResource(R.string.assistant_hint_interrupt, phaseLabel)
                    busy -> stringResource(R.string.assistant_hint_busy, phaseLabel)
                    else -> stringResource(R.string.assistant_hint_idle)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Не блокируем, когда очки не подключены: кнопка сама скажет «Очки не подключены» и что делать.
                SideAction(R.drawable.ic_sym_visibility, stringResource(R.string.what_do_i_see), enabled = !busy && hasApiKey, onClick = onWhatDoISee)
                MicButton(
                    phase = state.phase,
                    level = micLevel,
                    enabled = busy || hasApiKey,
                    onClick = if (busy && !state.phase.interruptible) viewModel::stop else onAsk,
                )
                SideAction(
                    R.drawable.ic_sym_add_comment,
                    stringResource(R.string.new_conversation),
                    enabled = state.chat.isNotEmpty() || state.pendingQuestion.isNotEmpty(),
                    onClick = viewModel::newConversation,
                )
            }
        }
    }

    photo?.let { (bitmap, source) -> FullscreenPhoto(bitmap, source?.let { stringResource(it.chatLabelRes) }) { photo = null } }
}

/** Боковая круглая кнопка с подписью под ней (зона нажатия ≥ 48 dp). */
@Composable
private fun SideAction(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp)) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(30.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
private fun Chat(state: UiState, modifier: Modifier, onOpenPhoto: (Bitmap, ImageSource?) -> Unit) {
    val listState = rememberLazyListState()
    val hasPending = state.pendingQuestion.isNotEmpty() || state.pendingFrame != null
    val rowCount = state.chat.size + (if (hasPending) 1 else 0) + (if (state.error != null) 1 else 0)
    LaunchedEffect(rowCount, state.pendingQuestion, state.pendingAnswer) {
        if (rowCount > 0) {
            // К концу последней строки, а не к её началу: ответ с подробностями бывает выше экрана.
            listState.animateScrollToItem(rowCount - 1)
            listState.animateScrollBy(SCROLL_TO_END_PX)
        }
    }

    if (rowCount == 0) {
        Text(
            stringResource(R.string.assistant_empty),
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val firstRemembered = state.chat.indexOfFirst { it.remembered }
    LazyColumn(modifier = modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(state.chat, key = { _, item -> item.id }) { index, item ->
            if (index == firstRemembered && firstRemembered > 0) {
                ForgottenDivider()
            }
            Column(
                modifier = Modifier.alpha(if (item.remembered) 1f else 0.4f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UserBubble(item.question, item.frame, item.frameSource, onOpenPhoto)
                key(item.id) { AnswerBubble(item.answer, item.details, item.model, item.interrupted) }
            }
        }
        if (hasPending) {
            item(key = "pending") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(state.pendingQuestion.ifEmpty { "…" }, state.pendingFrame, state.pendingFrameSource, onOpenPhoto)
                    // Ответ появляется по предложениям, пока модель ещё пишет.
                    if (state.pendingAnswer.isNotEmpty()) AnswerBubble(state.pendingAnswer, state.pendingDetails, null, interrupted = false)
                }
            }
        }
        state.error?.let { error -> item(key = "error") { Notice(error) } }
    }
}

@Composable
private fun ForgottenDivider() {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stringResource(R.string.forgotten_divider),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** Моя реплика — справа. Снимок со скруглёнными углами, по нажатию — на весь экран; источник — мелко под пузырём. */
@Composable
private fun UserBubble(text: String, frame: Bitmap?, frameSource: ImageSource?, onOpenPhoto: (Bitmap, ImageSource?) -> Unit) {
    val sourceLabel = stringResource((frameSource ?: ImageSource.GLASSES).chatLabelRes)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        frame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_with_source, sourceLabel),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = stringResource(R.string.open_photo_full_screen)) { onOpenPhoto(bitmap, frameSource) },
                )
            }
            Text(
                text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (frameSource != null) BubbleCaption(stringResource(R.string.photo_caption, sourceLabel))
    }
}

/**
 * Ответ модели — слева. Суть (она звучала) — обычным текстом; подробности — по «Подробнее», мельче.
 * Метка модели и «перебит» — мелко под пузырём.
 */
@Composable
private fun AnswerBubble(text: String, details: String, model: ModelTag?, interrupted: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text.ifEmpty { "…" }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (details.isNotEmpty()) {
                if (expanded) {
                    Text(
                        details,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(if (expanded) R.string.answer_less else R.string.answer_more))
                }
            }
        }
        val caption = listOfNotNull(
            model?.let { modelLabel(it) },
            if (interrupted) stringResource(R.string.answer_interrupted) else null,
        ).joinToString(" · ")
        if (caption.isNotEmpty()) BubbleCaption(caption)
    }
}

@Composable
private fun BubbleCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** Источник снимков: выбор, состояние очков и видоискатель камеры телефона. */
@Composable
private fun SourceCard(
    source: ImageSource,
    sources: List<ImageSource>,
    status: GlassesStatus,
    camera: PhoneCamera,
    cameraGranted: Boolean,
    enabled: Boolean,
    onSelect: (ImageSource) -> Unit,
    onConnectGlasses: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.photos_label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SourcePicker(source, sources, enabled, onSelect)
            }
            when (source) {
                ImageSource.GLASSES, ImageSource.SIMULATOR -> {
                    Text(stringResource(status.textRes), style = MaterialTheme.typography.bodyMedium)
                    if (source == ImageSource.GLASSES && !status.ready) {
                        FilledTonalButton(onClick = onConnectGlasses, enabled = enabled) {
                            Icon(painterResource(R.drawable.ic_sym_bluetooth_connected), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.connect_glasses))
                        }
                    }
                }
                ImageSource.PHONE_CAMERA -> if (cameraGranted) {
                    PhoneViewfinder(camera)
                } else {
                    // Доступ отозвали в настройках Android — объясняем вместо чёрного видоискателя.
                    Text(stringResource(R.string.no_camera_access), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { onSelect(ImageSource.PHONE_CAMERA) }, enabled = enabled) { Text(stringResource(R.string.allow)) }
                }
                ImageSource.GALLERY -> Text(
                    stringResource(R.string.gallery_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SourcePicker(selected: ImageSource, sources: List<ImageSource>, enabled: Boolean, onSelect: (ImageSource) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(stringResource(selected.titleRes) + " ▾", maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(source.titleRes).let { if (source.debugOnly) stringResource(R.string.source_debug_suffix, it) else it },
                            fontWeight = if (source == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(source)
                    },
                )
            }
        }
    }
}

/** Небольшой видоискатель: камера работает, только пока он на экране. */
@Composable
private fun PhoneViewfinder(camera: PhoneCamera) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        camera.bind(lifecycleOwner, previewView)
        onDispose { camera.unbind() }
    }
    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Прокрутка «до упора»: список сам ограничит её концом. */
private const val SCROLL_TO_END_PX = 100_000f
