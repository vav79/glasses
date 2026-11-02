package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.Role
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import ru.vasev.glasses.llm.WebSource
import ru.vasev.glasses.links.FoundLink
import ru.vasev.glasses.links.LinkOpener
import ru.vasev.glasses.notes.Note
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassBackgroundText
import ru.vasev.glasses.ui.glass.glassContentSource
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Разрешение на камеру телефона (спрашивает Activity); true — можно открыть видоискатель. */
    requestPhoneCamera: suspend () -> Boolean,
    /** «Заметки» — из меню ⋮ шапки. */
    notesOpen: Boolean,
    onCloseNotes: () -> Unit,
    /** Пока идёт перевод — низ экрана «Переводчика» вместо поля ввода. */
    translateBar: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val allLabels by viewModel.showAllModelLabels.collectAsStateWithLifecycle()
    val busy = state.phase != Phase.IDLE
    var photo by remember { mutableStateOf<Pair<Bitmap, String?>?>(null) }
    var fullText by remember { mutableStateOf<String?>(null) }
    var partnerText by remember { mutableStateOf<String?>(null) }
    val attachment by viewModel.attachment.collectAsStateWithLifecycle()
    var viewfinder by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasApiKey) MissingKeyNotice(model, onOpenSettings)

            // Чат уходит под нижнюю стеклянную панель — она его размывает.
            Chat(
                state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                defaultModel = ModelCatalog.default(Role.ASSISTANT).title,
                allLabels = allLabels,
                onOpenPhoto = { bitmap, label -> photo = bitmap to label },
                onOpenText = { fullText = it },
                onOpenLink = { LinkOpener.openSource(context, it) },
                onLinkClick = viewModel::requestLink,
                onRoute = { openRoute(context, it) },
                onSpeak = viewModel::speakAnswer,
                onShowPartner = { partnerText = it },
            )
        }

        if (translateBar != null) translateBar() else Composer(
            phase = state.phase,
            micLevel = micLevel,
            hasApiKey = hasApiKey,
            attachment = attachment,
            onAsk = onAsk,
            onStop = viewModel::stop,
            onSend = viewModel::askText,
            onPhoneCamera = { scope.launch { if (requestPhoneCamera()) viewfinder = true } },
            onGallery = { viewModel.attach(ImageSource.GALLERY) },
            onGlasses = { viewModel.attach(viewModel.glassesSource()) },
            onRemoveAttachment = viewModel::clearAttachment,
            onDescribe = viewModel::describeAttachment,
            onTranslate = viewModel::translateAttachment,
            onRemember = viewModel::rememberAttachment,
            onOpenAttachment = { photo = it to null },
        )
    }
    AssistantOverlays(viewModel)
    if (viewfinder) {
        ComposerViewfinder(
            camera = viewModel.phoneCamera,
            // Видоискатель закрывается после снимка: камера привязана к нему и нужна до конца съёмки.
            onShoot = {
                scope.launch {
                    viewModel.attach(ImageSource.PHONE_CAMERA).join()
                    viewfinder = false
                }
            },
            onBack = { viewfinder = false },
        )
    }
    if (notesOpen) {
        NotesScreen(
            viewModel = viewModel,
            onClose = onCloseNotes,
            onOpenPhoto = { bitmap -> photo = bitmap to null },
            onRoute = { openRoute(context, it) },
        )
    }
    photo?.let { (bitmap, label) -> FullscreenPhoto(bitmap, label) { photo = null } }
    fullText?.let { text -> FullscreenText(text, stringResource(R.string.translate_what_i_see)) { fullText = null } }
    partnerText?.let { text -> FullscreenText(text, "", facingPartner = true) { partnerText = null } }
}

/**
 * Общее для «Ассистента» и «Камеры»: проверка ссылки перед открытием и само открытие — только отсюда, после
 * подтверждения на карточке (кнопкой или голосом).
 */
@Composable
fun AssistantOverlays(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.openLink.collect { LinkOpener.open(context, it) } }
    state.confirmLink?.let { link ->
        LinkConfirmDialog(link, onOpen = { viewModel.confirmLink(link) }, onDismiss = viewModel::dismissLink)
    }
}

/** Тонкий разделитель в ленте: смена профиля или перевода. */
@Composable
private fun ChatDivider(text: String) {
    val p = ru.vasev.glasses.ui.theme.LocalSputnikColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = p.divider)
        Text(text, style = MaterialTheme.typography.labelMedium, color = p.text2, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = p.divider)
    }
}

@Composable
private fun Chat(
    state: UiState,
    modifier: Modifier,
    /** Основная модель ассистента: её метку под ответом не показываем (кроме роли «Разработчик»). */
    defaultModel: String,
    allLabels: Boolean,
    onOpenPhoto: (Bitmap, String?) -> Unit,
    onOpenText: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onLinkClick: (FoundLink) -> Unit,
    onRoute: (Note) -> Unit,
    onSpeak: (Long) -> Unit,
    onShowPartner: (String) -> Unit,
) {
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

    val firstRemembered = state.chat.indexOfFirst { it.remembered }
    // Список есть всегда, и в пустом чате тоже: он — источник размытия для панелей, а убранный источник
    // роняет Haze («LayoutCoordinate operations are only valid when isAttached»), например после «Новый разговор».
    LazyColumn(
        modifier = modifier.glassContentSource(),
        state = listState,
        // Снизу — место под панель управления: последний ответ можно докрутить над ней.
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (rowCount == 0) {
            // Пустой чат — короткая подсказка мелким шрифтом, без карточки, посередине.
            item(key = "empty") {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.assistant_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = glassBackgroundText(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        itemsIndexed(state.chat, key = { _, item -> item.id }) { index, item ->
            if (item.divider != null) {
                ChatDivider(item.divider)
                return@itemsIndexed
            }
            if (item.translated != null) {
                TranslatedBubble(item.translated, onShowPartner)
                return@itemsIndexed
            }
            if (index == firstRemembered && firstRemembered > 0) {
                ForgottenDivider()
            }
            Column(
                modifier = Modifier.alpha(if (item.remembered) 1f else 0.4f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UserBubble(item.question, item.frame, item.frameSource, onOpenPhoto)
                if (item.links.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.links.forEach { link -> LinkChip(link) { onLinkClick(link) } }
                    }
                }
                key(item.id) {
                    AnswerBubble(
                        item.answer,
                        item.details,
                        // Метка — только когда ответила не основная модель (запасная, DeepSeek, без поиска) или разработчику.
                        item.model?.takeIf { allLabels || it.viaFallback || it.autoVision || it.noWebSearch || it.title != defaultModel },
                        item.interrupted,
                        translation = item.translation,
                        ratesNote = item.ratesNote,
                        onOpenText = onOpenText,
                        onSpeak = if (item.silent) ({ onSpeak(item.id) }) else null,
                    )
                }
                if (item.sources.isNotEmpty()) SourceLinks(item.sources, onOpenLink)
                item.notes.forEach { card -> NoteCardView(card, onOpenPhoto, onRoute) }
                item.deletedNotes.forEach { BubbleCaption(stringResource(R.string.note_deleted, it)) }
            }
        }
        if (hasPending) {
            item(key = "pending") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(state.pendingQuestion.ifEmpty { "…" }, state.pendingFrame, state.pendingFrameSource, onOpenPhoto)
                    // Ответ появляется по предложениям, пока модель ещё пишет.
                    if (state.pendingAnswer.isNotEmpty()) {
                        AnswerBubble(
                            state.pendingAnswer,
                            state.pendingDetails,
                            null,
                            interrupted = false,
                            translation = state.pendingTranslation,
                            ratesNote = null,
                            onOpenText = onOpenText,
                        )
                    }
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
private fun UserBubble(text: String, frame: Bitmap?, frameSource: ImageSource?, onOpenPhoto: (Bitmap, String?) -> Unit) {
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
                        .clickable(onClickLabel = stringResource(R.string.open_photo_full_screen)) {
                            onOpenPhoto(bitmap, frameSource?.let { sourceLabel })
                        },
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
private fun AnswerBubble(
    text: String,
    details: String,
    model: ModelTag?,
    interrupted: Boolean,
    translation: Boolean,
    ratesNote: RatesNote?,
    onOpenText: (String) -> Unit,
    /** Вопрос был текстом: ответ не звучал — «Озвучить» по кнопке. */
    onSpeak: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    // Перевод снимка — ради полного текста: он виден сразу.
    var expanded by rememberSaveable { mutableStateOf(translation) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .glass(RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text.ifEmpty { "…" }, color = c.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (details.isEmpty() && onSpeak != null) TextAction(stringResource(R.string.speak_answer), onSpeak)
            if (details.isNotEmpty()) {
                if (expanded) {
                    Text(
                        details,
                        color = glassAccent(accent = c.onSurfaceVariant),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextAction(stringResource(if (expanded) R.string.answer_less else R.string.answer_more)) { expanded = !expanded }
                    if (translation) TextAction(stringResource(R.string.show_translation_full_screen)) { onOpenText(details) }
                    if (onSpeak != null) TextAction(stringResource(R.string.speak_answer), onSpeak)
                }
            }
        }
        val caption = listOfNotNull(
            model?.let { modelLabel(it) },
            ratesNote?.let { stringResource(if (it.offline) R.string.rates_note_offline else R.string.rates_note, DATE.format(it.date)) },
            if (interrupted) stringResource(R.string.answer_interrupted) else null,
        ).joinToString(" · ")
        if (caption.isNotEmpty()) BubbleCaption(caption)
    }
}

/** Текстовая кнопка внутри пузыря (зона нажатия ≥ 48 dp по высоте). */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = glassAccent(),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(GlassShapes.pill)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

/** Ссылки веб-поиска под ответом — свёрнутым чипом «Источники (N)»; раскрытые: заголовок и настоящий домен. */
@Composable
internal fun SourceLinks(sources: List<WebSource>, onOpen: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .glass(GlassShapes.pill)
                .clip(GlassShapes.pill)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.sources_count, sources.size), style = MaterialTheme.typography.labelLarge)
            Icon(
                painterResource(R.drawable.ic_sym_expand_more),
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
        }
        if (open) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { source ->
                // Нажатие — уже подтверждение, поэтому настоящий домен виден на кнопке всегда.
                val domain = sourceDomain(source.url)
                val title = source.title.ifBlank { domain }
                Row(
                    Modifier
                        .widthIn(max = 300.dp)
                        .glass(GlassShapes.pill)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(source.url) }
                        .semantics(mergeDescendants = true) { contentDescription = "$domain: $title" }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_sym_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).height(18.dp),
                    )
                    Column {
                        Text(
                            title.take(MAX_LINK_TITLE).let { if (title.length > MAX_LINK_TITLE) "$it…" else it },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Text(domain, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Заметка в чате: снимок (по нажатию — на весь экран), текст, время и «Проложить маршрут». */
@Composable
fun NoteCardView(card: NoteCard, onOpenPhoto: (Bitmap, String?) -> Unit, onRoute: (Note) -> Unit) {
    NoteBody(
        note = card.note,
        photo = card.photo,
        title = stringResource(if (card.saved) R.string.note_saved else R.string.note_found),
        onOpenPhoto = { onOpenPhoto(it, null) },
        onRoute = onRoute,
        modifier = Modifier.widthIn(max = 320.dp),
    )
}

/** Карточка заметки — общая для чата и экрана «Заметки». */
@Composable
fun NoteBody(
    note: Note,
    photo: Bitmap?,
    title: String?,
    onOpenPhoto: (Bitmap) -> Unit,
    onRoute: (Note) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    GlassCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
                Text(
                    noteTime().format(note.createdAt.atZone(java.time.ZoneId.systemDefault())) +
                        (note.accuracyM?.let { " · " + stringResource(R.string.note_accuracy, it.toInt()) } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            trailing?.invoke()
        }
        photo?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.note_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = stringResource(R.string.open_photo_full_screen)) { onOpenPhoto(bitmap) },
            )
        }
        Text(
            note.text.ifEmpty { stringResource(R.string.note_no_text) },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (note.hasLocation) {
            GlassButton(
                stringResource(R.string.note_route),
                onClick = { onRoute(note) },
                icon = R.drawable.ic_sym_directions,
                kind = GlassButtonKind.PRIMARY,
            )
        }
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

@Composable
private fun Notice(text: String) {
    val c = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth(), tint = c.errorContainer, text = c.onErrorContainer, contentPadding = PaddingValues(14.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
/** Время заметки на языке приложения (а не системы). */
@Composable
private fun noteTime(): DateTimeFormatter {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale) }
}
private const val MAX_LINK_TITLE = 32

/** Прокрутка «до упора»: список сам ограничит её концом. */
private const val SCROLL_TO_END_PX = 100_000f

/** Высота нижней панели управления: подсказка, капля и подписи кнопок. */
private val CHAR_NEWLINE = 0x0A.toChar()
private val CHAR_SPACE = 0x20.toChar()
