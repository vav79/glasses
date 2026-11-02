package ru.vasev.glasses.ui.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.vasev.glasses.R
import ru.vasev.glasses.chats.ChatRules
import ru.vasev.glasses.ui.theme.LocalSputnikColors
import ru.vasev.glasses.ui.theme.Manrope

/** Пункты верха бокового меню. Пока не перенесены, «Медиа», «Умения» и «Очки» ведут на старые экраны. */
enum class MenuItem(val titleRes: Int, @DrawableRes val icon: Int) {
    NEW_CHAT(R.string.menu_new_chat, R.drawable.ic_sym_edit_square),
    MEDIA(R.string.menu_media, R.drawable.ic_sym_photo_library),
    SKILLS(R.string.menu_skills, R.drawable.ic_sym_auto_awesome),
    CALENDAR(R.string.menu_calendar, R.drawable.ic_sym_calendar_month),
    GLASSES(R.string.menu_glasses, R.drawable.ic_sym_eyeglasses),
}

/** Статус модели в чипе внизу меню. */
enum class ModelStatus(val labelRes: Int) {
    AVAILABLE(R.string.model_status_available),
    SLOW(R.string.model_status_slow),
    UNAVAILABLE(R.string.model_status_unavailable),
    NO_KEY(R.string.model_status_no_key),
}

/** Строка списка «Чаты». */
data class ChatEntry(val id: String, val title: String, val selected: Boolean, val fromGlasses: Boolean = false, val unread: Boolean = false)

/** Что показать в шапке: на главном экране — полная шапка, на остальных — ☰ и заголовок. */
sealed interface ShellHeader {
    data class Chat(
        val profileTitle: String,
        val translateOn: Boolean,
        val glassesReady: Boolean,
        val glassesBattery: Int?,
        val onProfile: () -> Unit,
        val onTranslate: () -> Unit,
        val onGlasses: () -> Unit,
        /** Пункты меню ⋮: подпись → действие. */
        val chatMenu: List<Pair<String, () -> Unit>>,
    ) : ShellHeader

    data class Title(val title: String) : ShellHeader
}

/**
 * Каркас «Классики»: боковое меню (☰), шапка и экран. [onBack] — «Назад» на экране, кроме главного
 * (возврат в чат); открытое меню «Назад» закрывает само.
 */
@Composable
fun AppShell(
    header: ShellHeader,
    /** Чаты по запросу поиска (пустой — все), свежие первыми. */
    chats: (query: String) -> List<ChatEntry>,
    onShowAllChats: () -> Unit,
    onChatLongPress: (ChatEntry) -> Unit,
    modelName: String,
    modelStatus: ModelStatus,
    onMenuItem: (MenuItem) -> Unit,
    onChat: (ChatEntry) -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onModel: () -> Unit,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }
    val p = LocalSputnikColors.current
    if (onBack != null) BackHandler(enabled = drawer.isClosed, onBack = onBack)
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawer,
                drawerContainerColor = p.menu,
                drawerContentColor = p.text,
                drawerShape = RectangleShape,
                modifier = Modifier.width(MENU_WIDTH),
            ) {
                DrawerContent(
                    chats = chats,
                    onShowAll = { closeThen(onShowAllChats) },
                    onChatLongPress = onChatLongPress,
                    modelName = modelName,
                    modelStatus = modelStatus,
                    onMenuItem = { closeThen { onMenuItem(it) } },
                    onChat = { closeThen { onChat(it) } },
                    onNotifications = onNotifications,
                    onSettings = { closeThen(onSettings) },
                    onModel = { closeThen(onModel) },
                )
            }
        },
    ) {
        // Цвет текста по умолчанию — основной: без этого текст вне Surface был бы чёрным и в тёмной теме.
        CompositionLocalProvider(LocalContentColor provides p.text) {
            Column(Modifier.fillMaxSize().background(p.screen).safeDrawingPadding()) {
                Header(header, onMenu = { scope.launch { drawer.open() } })
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
private fun Header(header: ShellHeader, onMenu: () -> Unit) {
    val p = LocalSputnikColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RoundIcon(R.drawable.ic_sym_menu, stringResource(R.string.menu_open), onMenu, background = Color.Transparent)
        when (header) {
            is ShellHeader.Title -> Text(
                header.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            is ShellHeader.Chat -> {
                // Чип с кнопкой перевода — по центру свободного места; при крупном шрифте сжимается чип
                // (название — с многоточием), а очки и ⋮ остаются на месте.
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    ProfileChip(header.profileTitle, header.onProfile, Modifier.weight(1f, fill = false))
                    TranslateButton(header.translateOn, header.onTranslate)
                }
                GlassesBadge(header.glassesReady, header.glassesBattery, header.onGlasses)
                ChatMenu(header.chatMenu)
            }
        }
    }
    HorizontalDivider(color = p.divider)
}

/** Чип профиля: иконка, название, ▾. */
@Composable
private fun ProfileChip(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val p = LocalSputnikColors.current
    Row(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(max = 200.dp)
            .clip(CircleShape)
            .background(p.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Иконка — только у выбранного профиля (этап 4); «Обычный чат» без иконки — так название помещается
        // и при крупном системном шрифте.
        if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        // При крупном шрифте название сначала уменьшается (до 11 sp) и только потом обрезается.
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(painterResource(R.drawable.ic_sym_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** Кнопка перевода: выключено — тонкий контур и приглушённая иконка, включено — зелёная заливка. */
@Composable
private fun TranslateButton(on: Boolean, onClick: () -> Unit) {
    val p = LocalSputnikColors.current
    val description = stringResource(if (on) R.string.header_translate_on else R.string.header_translate_off)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(if (on) Modifier.background(p.on) else Modifier.border(BorderStroke(1.dp, p.iconMuted), CircleShape))
            .clickable(role = Role.Switch, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_sym_translate), contentDescription = null, tint = if (on) p.screen else p.text2, modifier = Modifier.size(22.dp))
    }
}

/** Очки с зарядом: нажатие — экран «Очки». Не подключены — приглушённая иконка без процента. */
@Composable
private fun GlassesBadge(ready: Boolean, battery: Int?, onClick: () -> Unit) {
    val p = LocalSputnikColors.current
    val description = when {
        !ready -> stringResource(R.string.glasses_off)
        battery != null -> stringResource(R.string.glasses_on_battery, battery)
        else -> stringResource(R.string.glasses_on)
    }
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_sym_eyeglasses), contentDescription = null, tint = if (ready) p.text else p.iconMuted, modifier = Modifier.size(22.dp))
        if (ready && battery != null) Text("$battery%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChatMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIcon(R.drawable.ic_sym_more_vert, stringResource(R.string.chat_menu), { open = true }, background = Color.Transparent, enabled = items.isNotEmpty())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    action()
                })
            }
        }
    }
}

@Composable
private fun RoundIcon(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    background: Color = LocalSputnikColors.current.surface,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    enabled: Boolean = true,
) {
    val p = LocalSputnikColors.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = if (enabled) p.text else p.iconMuted, modifier = Modifier.size(22.dp))
    }
}

/** Содержимое меню по макету «Классика»: поиск, пункты, «Чаты», низ с 🔔 ⚙️ и чипом модели. */
@Composable
private fun DrawerContent(
    chats: (String) -> List<ChatEntry>,
    onShowAll: () -> Unit,
    onChatLongPress: (ChatEntry) -> Unit,
    modelName: String,
    modelStatus: ModelStatus,
    onMenuItem: (MenuItem) -> Unit,
    onChat: (ChatEntry) -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onModel: () -> Unit,
) {
    val p = LocalSputnikColors.current
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxHeight().safeDrawingPadding().padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp)) {
        SearchField(query, { query = it })
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MenuItem.entries.forEach { item -> MenuRow(item, onClick = { onMenuItem(item) }) }
        }
        Text(
            stringResource(R.string.menu_chats),
            style = TextStyle(fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = p.text2,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
        )
        // Поиск — по названиям и тексту чатов; в меню — последние семь, остальные — «Показать все».
        val found = chats(query)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (found.isEmpty() && query.isNotBlank()) {
                Text(stringResource(R.string.chats_not_found), color = p.text2, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            found.take(RECENT_CHATS).forEach { chat -> ChatRow(chat, onLongClick = { onChatLongPress(chat) }) { onChat(chat) } }
            if (found.size > RECENT_CHATS) ShowAllRow(onShowAll)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawLine(p.divider, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(start = 4.dp, end = 4.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(R.drawable.ic_sym_notifications, stringResource(R.string.menu_notifications), onNotifications, size = 52.dp)
            RoundIcon(R.drawable.ic_sym_settings, stringResource(R.string.tab_settings), onSettings, size = 52.dp)
            Spacer(Modifier.weight(1f))
            ModelChip(modelName, modelStatus, onModel)
        }
    }
}

@Composable
private fun ShowAllRow(onClick: () -> Unit) {
    val p = LocalSputnikColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.chats_show_all), color = p.text2, style = TextStyle(fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null, tint = p.text2, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun SearchField(query: String, onChange: (String) -> Unit) {
    val p = LocalSputnikColors.current
    val hint = stringResource(R.string.menu_search)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(p.surface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_sym_search), contentDescription = null, tint = p.text2, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = p.text2, style = TextStyle(fontFamily = Manrope, fontSize = 16.sp))
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = Manrope, fontSize = 16.sp, color = p.text),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Text(stringResource(item.titleRes), style = TextStyle(fontFamily = Manrope, fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Строка чата: нажатие — открыть, долгое нажатие — удалить (с подтверждением). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRow(chat: ChatEntry, onLongClick: () -> Unit, onClick: () -> Unit) {
    val p = LocalSputnikColors.current
    val deleteLabel = stringResource(R.string.chat_delete)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (chat.selected) Modifier.background(p.selected) else Modifier)
            .combinedClickable(role = Role.Button, onLongClickLabel = deleteLabel, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            chat.title,
            style = TextStyle(fontFamily = Manrope, fontSize = 16.sp, fontWeight = if (chat.unread) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (chat.fromGlasses) Icon(painterResource(R.drawable.ic_sym_eyeglasses), contentDescription = stringResource(R.string.chat_from_glasses), tint = p.iconMuted, modifier = Modifier.size(20.dp))
        if (chat.unread) {
            val unread = stringResource(R.string.chat_unread)
            Box(Modifier.size(9.dp).clip(CircleShape).background(p.accent).semantics { contentDescription = unread })
        }
    }
}

/** Чип активной модели: точка статуса с ореолом, название и статус текстом. Нажатие — Настройки → ИИ-ассистенты. */
@Composable
private fun ModelChip(name: String, status: ModelStatus, onClick: () -> Unit) {
    val p = LocalSputnikColors.current
    val color = when (status) {
        ModelStatus.AVAILABLE -> p.ok
        ModelStatus.SLOW -> p.warn
        ModelStatus.UNAVAILABLE -> p.err
        ModelStatus.NO_KEY -> p.off
    }
    val label = stringResource(status.labelRes)
    val description = stringResource(R.string.model_chip_cd, name, label)
    Row(
        Modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(p.surface)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 16.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)))
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = TextStyle(fontFamily = Manrope, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(label, style = TextStyle(fontFamily = Manrope, fontSize = 12.sp, fontWeight = FontWeight.Medium), color = p.text2, maxLines = 1)
        }
    }
}

/**
 * «Удалить чат «‹название›»?» — переписка удаляется безвозвратно, снимки и видео остаются в «Медиа».
 * Кнопка «Удалить» — красная.
 */
@Composable
fun DeleteChatDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalSputnikColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.menu,
        title = { Text(stringResource(R.string.chat_delete_title, title)) },
        text = { Text(stringResource(R.string.chat_delete_text), color = p.text2) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = p.err)) {
                Text(stringResource(R.string.delete), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** «Показать все»: все чаты с поиском. */
@Composable
fun AllChatsScreen(chats: (String) -> List<ChatEntry>, onChat: (ChatEntry) -> Unit, onLongPress: (ChatEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    val found = chats(query)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        SearchField(query, { query = it })
        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(found, key = { it.id }) { chat -> ChatRow(chat, onLongClick = { onLongPress(chat) }) { onChat(chat) } }
        }
        if (found.isEmpty()) {
            Text(
                stringResource(if (query.isBlank()) R.string.chats_empty else R.string.chats_not_found),
                color = LocalSputnikColors.current.text2,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** Название чата для шапки — по тем же правилам, что в истории ([ChatRules.title]). */
fun chatTitle(question: String): String = ChatRules.title(question)

private val MENU_WIDTH = 320.dp
private const val RECENT_CHATS = 7
