package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.PhoneCamera
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.theme.LocalSputnikColors

/**
 * Поле ввода главного экрана — три способа спросить: текст, голос (капля микрофона), камера
 * (телефон, галерея или «Снять очками»). Снимок прикрепляется к полю: с ним уходит следующий вопрос
 * или одно из действий над ним (описать, перевести текст, запомнить — бывшие кнопки «Камеры»).
 */
@Composable
fun Composer(
    phase: Phase,
    micLevel: Float,
    hasApiKey: Boolean,
    attachment: MainViewModel.Attachment?,
    onAsk: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onPhoneCamera: () -> Unit,
    onGallery: () -> Unit,
    onGlasses: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onDescribe: () -> Unit,
    onTranslate: () -> Unit,
    onRemember: () -> Unit,
    onOpenAttachment: (Bitmap) -> Unit,
) {
    val p = LocalSputnikColors.current
    val busy = phase != Phase.IDLE
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isBlank()) return
        onSend(text)
        text = ""
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Состояние — короткой подписью: «слушаю», «думаю… нажмите, чтобы перебить» и т. п.
        val phaseLabel = phase.labelRes?.let { stringResource(it) }.orEmpty()
        if (busy) {
            Text(
                if (phase.interruptible) stringResource(R.string.status_interrupt, phaseLabel) else stringResource(R.string.status_busy, phaseLabel),
                style = MaterialTheme.typography.labelLarge,
                color = p.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (attachment != null) {
            AttachmentRow(attachment, enabled = !busy && hasApiKey, onRemoveAttachment, onDescribe, onTranslate, onRemember, onOpenAttachment)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraMenu(enabled = !busy, onPhoneCamera, onGallery, onGlasses)
            // Поле — пилюля на поверхности «Классики»; Enter — «Отправить».
            val hint = stringResource(R.string.composer_hint)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(p.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(hint, color = p.text2, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = hasApiKey,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            if (text.isNotBlank()) {
                GlassIconButton(
                    R.drawable.ic_sym_send,
                    stringResource(R.string.send),
                    onClick = ::send,
                    size = 56.dp,
                    enabled = hasApiKey && !busy,
                    kind = GlassButtonKind.PRIMARY,
                )
            } else {
                // Во время ответа капля перебивает его и слушает новый вопрос; пока снимаем или слушаем — останавливает.
                MicButton(
                    phase = phase,
                    level = micLevel,
                    enabled = busy || hasApiKey,
                    onClick = if (busy && !phase.interruptible) onStop else onAsk,
                    diameter = 56.dp,
                )
            }
        }
    }
}

/** Камера в поле ввода: только по нажатию — меню из трёх источников. */
@Composable
private fun CameraMenu(enabled: Boolean, onPhoneCamera: () -> Unit, onGallery: () -> Unit, onGlasses: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassIconButton(R.drawable.ic_sym_photo_camera, stringResource(R.string.composer_camera), onClick = { open = true }, size = 52.dp, enabled = enabled)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                Triple(R.string.attach_phone_camera, R.drawable.ic_sym_photo_camera, onPhoneCamera),
                Triple(R.string.attach_gallery, R.drawable.ic_sym_photo_library, onGallery),
                Triple(R.string.attach_glasses, R.drawable.ic_sym_eyeglasses, onGlasses),
            ).forEach { (label, icon, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** Прикреплённый снимок: миниатюра (нажатие — на весь экран, ✕ — убрать) и действия над ним. */
@Composable
private fun AttachmentRow(
    attachment: MainViewModel.Attachment,
    enabled: Boolean,
    onRemove: () -> Unit,
    onDescribe: () -> Unit,
    onTranslate: () -> Unit,
    onRemember: () -> Unit,
    onOpen: (Bitmap) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Image(
                attachment.preview.asImageBitmap(),
                contentDescription = stringResource(R.string.attach_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(attachment.preview) },
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.TopEnd,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sym_close),
                    contentDescription = stringResource(R.string.attach_remove),
                    tint = Color.White,
                    modifier = Modifier.padding(2.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(3.dp),
                )
            }
        }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassButton(stringResource(R.string.action_describe), onDescribe, icon = R.drawable.ic_sym_visibility, enabled = enabled, contentPadding = ACTION_PADDING)
            GlassButton(stringResource(R.string.action_translate_text), onTranslate, icon = R.drawable.ic_sym_translate, enabled = enabled, contentPadding = ACTION_PADDING)
            GlassButton(stringResource(R.string.action_remember), onRemember, icon = R.drawable.ic_sym_bookmark, enabled = enabled, contentPadding = ACTION_PADDING)
        }
    }
}

/** Видоискатель камеры телефона для снимка в поле ввода: затвор и «Назад». */
@Composable
fun ComposerViewfinder(camera: PhoneCamera, onShoot: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var shooting by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PhoneViewfinderFull(camera)
        Box(Modifier.safeDrawingPadding().padding(12.dp)) {
            GlassIconButton(R.drawable.ic_sym_arrow_back, stringResource(R.string.nav_back), onClick = onBack, size = 48.dp)
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)) {
            GlassIconButton(
                R.drawable.ic_sym_photo_camera,
                stringResource(R.string.composer_shoot),
                onClick = {
                    if (!shooting) {
                        shooting = true
                        onShoot()
                    }
                },
                size = 76.dp,
                kind = GlassButtonKind.PRIMARY,
            )
        }
    }
}

private val ACTION_PADDING = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
