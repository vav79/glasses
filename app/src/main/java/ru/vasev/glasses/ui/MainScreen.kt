package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.camera.PhoneCamera
import ru.vasev.glasses.glasses.GlassesStatus

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
    val busy = state.phase != Phase.IDLE

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Очки + Claude",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = viewModel::newConversation,
                    enabled = state.chat.isNotEmpty() || state.pendingQuestion.isNotEmpty(),
                ) { Text("Новый разговор") }
            }

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

            Chat(state, modifier = Modifier.weight(1f).fillMaxWidth())

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(state.phase.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::stop) { Text("Стоп") }
                }
            }

            Button(
                onClick = onAsk,
                enabled = !busy && hasApiKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) { Text("Спросить", fontSize = 22.sp) }

            FilledTonalButton(
                onClick = onWhatDoISee,
                // Не блокируем, когда очки не подключены: кнопка сама скажет «Очки не подключены» и что делать.
                enabled = !busy && hasApiKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) { Text("Что я вижу", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun Chat(state: UiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    val hasPending = state.pendingQuestion.isNotEmpty() || state.pendingFrame != null
    val rowCount = state.chat.size + (if (hasPending) 1 else 0) + (if (state.error != null) 1 else 0)
    LaunchedEffect(rowCount, state.pendingQuestion) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    if (rowCount == 0) {
        Text(
            "Нажмите «Спросить» и задайте вопрос голосом. «Что я вижу» сначала возьмёт снимок из выбранного источника. " +
                "Уточняющие вопросы Claude поймёт с учётом разговора. Сказать «новый разговор» или " +
                "«забудь всё» — начать заново.",
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
                UserBubble(item.question, item.frame, item.frameSource)
                AnswerBubble(item.answer, item.model)
            }
        }
        if (hasPending) {
            item(key = "pending") {
                UserBubble(state.pendingQuestion.ifEmpty { "…" }, state.pendingFrame, state.pendingFrameSource)
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
            "Выше — Claude уже не помнит",
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun UserBubble(text: String, frame: Bitmap?, frameSource: String? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Снимок: ${frameSource ?: "очки"}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)),
                )
                frameSource?.let { label ->
                    Text(
                        "снимок: $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AnswerBubble(text: String, model: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            Text(model.ifEmpty { "Ответ" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
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
                Text("Снимки:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SourcePicker(source, sources, enabled, onSelect)
            }
            when (source) {
                ImageSource.GLASSES, ImageSource.SIMULATOR -> {
                    Text(status.text, style = MaterialTheme.typography.bodyMedium)
                    if (source == ImageSource.GLASSES && !status.ready) {
                        FilledTonalButton(onClick = onConnectGlasses, enabled = enabled) { Text("Подключить очки через Meta AI") }
                    }
                }
                ImageSource.PHONE_CAMERA -> if (cameraGranted) {
                    PhoneViewfinder(camera)
                } else {
                    // Доступ отозвали в настройках Android — объясняем вместо чёрного видоискателя.
                    Text("Нет доступа к камере телефона.", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { onSelect(ImageSource.PHONE_CAMERA) }, enabled = enabled) { Text("Разрешить") }
                }
                ImageSource.GALLERY -> Text(
                    "«Что я вижу» откроет системный выбор фото — приложение видит только выбранный снимок.",
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
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("${selected.title} ▾", maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = {
                        Text(
                            source.title + if (source.debugOnly) " (debug)" else "",
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
