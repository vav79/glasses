package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.PhoneCamera
import ru.vasev.glasses.glasses.GlassesStatus
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassBackgroundText
import ru.vasev.glasses.ui.glass.glassContentSource

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

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = LocalTabBarInset.current)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.assistant_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
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

            // Чат уходит под нижнюю стеклянную панель — она его размывает.
            Chat(state, modifier = Modifier.weight(1f).fillMaxWidth(), onOpenPhoto = { bitmap, label -> photo = bitmap to label })
        }

        // Управление: «Что я вижу» — капля микрофона — «Новый разговор».
        // Пока идёт ответ, кнопка микрофона перебивает его и сразу слушает новый вопрос;
        // пока снимаем или слушаем — останавливает. Остальной экран (фото, «Подробнее») работает как обычно.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val phaseLabel = state.phase.labelRes?.let { stringResource(it) }.orEmpty()
            Text(
                when {
                    state.phase.interruptible -> stringResource(R.string.assistant_hint_interrupt, phaseLabel)
                    busy -> stringResource(R.string.assistant_hint_busy, phaseLabel)
                    else -> stringResource(R.string.assistant_hint_idle)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .glass(GlassShapes.pill, blur = true)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

/** Боковая круглая стеклянная кнопка с подписью под ней (зона нажатия ≥ 48 dp). */
@Composable
private fun SideAction(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        GlassIconButton(icon, label, onClick, size = 60.dp, enabled = enabled, blur = true)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .padding(top = 6.dp)
                // Подпись мелкая — размытие под ней не видно, а каждый размытый элемент перерисовывается при прокрутке чата.
                .glass(GlassShapes.pill, elevation = 0.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clearAndSetSemantics { },
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
        Box(modifier) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.assistant_empty), style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val firstRemembered = state.chat.indexOfFirst { it.remembered }
    LazyColumn(
        modifier = modifier.glassContentSource(),
        state = listState,
        // Снизу — место под панель управления: последний ответ можно докрутить над ней.
        contentPadding = PaddingValues(top = 4.dp, bottom = CONTROLS_SPACE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
    val color = glassBackgroundText()
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.4f))
        Text(
            stringResource(R.string.forgotten_divider),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.4f))
    }
}

/** Моя реплика — справа, стекло цвета акцента. Снимок по нажатию — на весь экран; источник — мелко под пузырём. */
@Composable
private fun UserBubble(text: String, frame: Bitmap?, frameSource: ImageSource?, onOpenPhoto: (Bitmap, ImageSource?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val sourceLabel = stringResource((frameSource ?: ImageSource.GLASSES).chatLabelRes)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .glass(RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp), tint = c.primaryContainer, text = c.onPrimaryContainer)
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
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClickLabel = stringResource(R.string.open_photo_full_screen)) { onOpenPhoto(bitmap, frameSource) },
                )
            }
            Text(
                text,
                color = c.onPrimaryContainer,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (frameSource != null) BubbleCaption(stringResource(R.string.photo_caption, sourceLabel))
    }
}

/**
 * Ответ модели — слева, светлое стекло. Суть (она звучала) — обычным текстом; подробности — по «Подробнее», мельче.
 * Метка модели и «перебит» — мелко под пузырём.
 */
@Composable
private fun AnswerBubble(text: String, details: String, model: ModelTag?, interrupted: Boolean) {
    val c = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .glass(RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text.ifEmpty { "…" }, color = c.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (details.isNotEmpty()) {
                if (expanded) {
                    Text(
                        details,
                        color = glassAccent(accent = c.onSurfaceVariant),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    stringResource(if (expanded) R.string.answer_less else R.string.answer_more),
                    color = glassAccent(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(GlassShapes.pill)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                )
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
        color = glassBackgroundText(),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
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
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.photos_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            SourcePicker(source, sources, enabled, onSelect)
        }
        when (source) {
            ImageSource.GLASSES, ImageSource.SIMULATOR -> {
                Text(stringResource(status.textRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
                if (source == ImageSource.GLASSES && !status.ready) {
                    GlassButton(
                        stringResource(R.string.connect_glasses),
                        onClick = onConnectGlasses,
                        icon = R.drawable.ic_sym_bluetooth_connected,
                        kind = GlassButtonKind.PRIMARY,
                        enabled = enabled,
                    )
                }
            }
            ImageSource.PHONE_CAMERA -> if (cameraGranted) {
                PhoneViewfinder(camera)
            } else {
                // Доступ отозвали в настройках Android — объясняем вместо чёрного видоискателя.
                Text(stringResource(R.string.no_camera_access), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
                GlassButton(stringResource(R.string.allow), onClick = { onSelect(ImageSource.PHONE_CAMERA) }, kind = GlassButtonKind.PRIMARY, enabled = enabled)
            }
            ImageSource.GALLERY -> Text(
                stringResource(R.string.gallery_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SourcePicker(selected: ImageSource, sources: List<ImageSource>, enabled: Boolean, onSelect: (ImageSource) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassButton(
            stringResource(selected.titleRes) + " ▾",
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        )
        // Меню — отдельное окно, размыть под ним приложение нельзя: оно непрозрачное, как в Material 3.
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
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
            .clip(RoundedCornerShape(18.dp)),
    )
}

@Composable
private fun Notice(text: String) {
    val c = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth(), tint = c.errorContainer, text = c.onErrorContainer, contentPadding = PaddingValues(14.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Прокрутка «до упора»: список сам ограничит её концом. */
private const val SCROLL_TO_END_PX = 100_000f

/** Высота нижней панели управления: подсказка, капля и подписи кнопок. */
private val CONTROLS_SPACE = 230.dp
