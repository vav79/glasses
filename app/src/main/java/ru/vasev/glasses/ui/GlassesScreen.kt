package ru.vasev.glasses.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.camera.ImageSource
import ru.vasev.glasses.speech.GestureState
import ru.vasev.glasses.speech.GesturesService
import ru.vasev.glasses.speech.MicChoice
import ru.vasev.glasses.speech.Tap
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.theme.LocalSputnikColors

/** Что показывает экран «Очки». */
data class GlassesScreenState(
    val ready: Boolean,
    val registered: Boolean,
    val statusText: Int,
    val name: String?,
    val battery: Int?,
    val notice: Int?,
    val gestureEnabled: Boolean,
    val gestureTap: Tap,
    val gestureState: GestureState,
    val photoSource: ImageSource,
    val photoSources: List<ImageSource>,
    val mic: MicChoice,
)

/**
 * Экран «Очки» (из меню и по значку в шапке): изображение очков со «свечением», подключение и отключение,
 * заряд, ошибки DAT, жест на дужке, источник снимка по голосу и жесту, микрофон для моего голоса.
 */
@Composable
fun GlassesScreen(
    state: GlassesScreenState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissNotice: () -> Unit,
    onGestureEnabled: (Boolean) -> Unit,
    onGestureTap: (Tap) -> Unit,
    onPhotoSource: (ImageSource) -> Unit,
    onMic: (MicChoice) -> Unit,
) {
    val p = LocalSputnikColors.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val described = stringResource(if (state.ready) R.string.glasses_art_on else R.string.glasses_art_off)
        GlassesArt(on = state.ready, modifier = Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = described })
        Text(
            buildString {
                append(stringResource(state.statusText))
                state.battery?.takeIf { state.ready }?.let { append(" · $it %") }
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        state.name?.takeIf { state.ready }?.let { Text(it, color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (state.registered) {
            GlassButton(stringResource(R.string.glasses_disconnect), onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth())
        } else {
            GlassButton(stringResource(R.string.connect_glasses), onClick = onConnect, kind = GlassButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
        }
        state.notice?.let { notice ->
            GlassCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.errorContainer, text = MaterialTheme.colorScheme.onErrorContainer) {
                Text(stringResource(notice), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.glasses_notice_close)) }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().toggleable(value = state.gestureEnabled, role = Role.Switch, onValueChange = onGestureEnabled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_gesture_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.settings_gesture_subtitle), style = MaterialTheme.typography.bodySmall, color = p.text2)
                }
                Switch(checked = state.gestureEnabled, onCheckedChange = null)
            }
            if (state.gestureEnabled) {
                Choices(listOf(Tap.DOUBLE to R.string.gesture_tap_double, Tap.SINGLE to R.string.gesture_tap_single), state.gestureTap, onGestureTap)
                Text(stringResource(GesturesService.stateText(state.gestureState, state.gestureTap)), style = MaterialTheme.typography.bodySmall, color = p.text2)
            }
            Text(stringResource(R.string.settings_gesture_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.glasses_photo_source), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Choices(state.photoSources.map { it to it.titleRes }, state.photoSource, onPhotoSource)
            Text(stringResource(R.string.glasses_photo_source_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.glasses_mic), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Choices(MicChoice.entries.filter { it.available }.map { it to it.titleRes }, state.mic, onMic)
            Text(stringResource(R.string.mic_auto_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
        }
        Text(stringResource(R.string.glasses_hey_meta_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.glasses_disconnect_title)) },
            text = { Text(stringResource(R.string.glasses_disconnect_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = p.err),
                ) { Text(stringResource(R.string.glasses_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun <T> Choices(choices: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { if (value != selected) onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

/**
 * Обобщённые очки (без логотипов и узнаваемого профиля): две линзы, перемычка, начало дужек.
 * Подключены — у камеры (левый верхний угол оправы) и на дужке «дышит» мягкое свечение; нет — оправа приглушена.
 */
@Composable
private fun GlassesArt(on: Boolean, modifier: Modifier) {
    val p = LocalSputnikColors.current
    val breath = rememberInfiniteTransition(label = "glow")
    val pulse by breath.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val glow = if (on) pulse else 0f
    val frame = if (on) p.text else p.iconMuted
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val lensW = w * 0.28f
        val lensH = h * 0.55f
        val gap = w * 0.08f
        val left = (w - lensW * 2 - gap) / 2
        val top = h * 0.25f
        val stroke = Stroke(width = h * 0.045f)
        val radius = CornerRadius(lensH * 0.35f)
        // Свечение у камеры и вдоль дужки — радиальные пятна цвета «включено».
        if (glow > 0f) {
            val camera = Offset(left + lensW * 0.12f, top + lensH * 0.1f)
            drawCircle(Brush.radialGradient(listOf(p.on.copy(alpha = 0.55f * glow), Color.Transparent), camera, h * 0.35f), h * 0.35f, camera)
            val temple = Offset(left + lensW * 2 + gap + w * 0.1f, top + lensH * 0.15f)
            drawCircle(Brush.radialGradient(listOf(p.on.copy(alpha = 0.35f * glow), Color.Transparent), temple, h * 0.25f), h * 0.25f, temple)
        }
        // Линзы — слегка затемнённые, оправа — линией.
        listOf(left, left + lensW + gap).forEach { x ->
            drawRoundRect(p.surface.copy(alpha = 0.6f), Offset(x, top), Size(lensW, lensH), radius)
            drawRoundRect(frame, Offset(x, top), Size(lensW, lensH), radius, style = stroke)
        }
        // Перемычка.
        val bridge = Path().apply {
            moveTo(left + lensW, top + lensH * 0.3f)
            quadraticTo(left + lensW + gap / 2, top + lensH * 0.05f, left + lensW + gap, top + lensH * 0.3f)
        }
        drawPath(bridge, frame, style = stroke)
        // Начало дужек.
        drawLine(frame, Offset(left, top + lensH * 0.15f), Offset(left - w * 0.1f, top + lensH * 0.05f), stroke.width)
        drawLine(frame, Offset(left + lensW * 2 + gap, top + lensH * 0.15f), Offset(left + lensW * 2 + gap + w * 0.1f, top + lensH * 0.05f), stroke.width)
        // Камера — маленькая точка в углу оправы.
        drawCircle(if (on) p.on else frame, h * 0.035f, Offset(left + lensW * 0.12f, top + lensH * 0.1f))
    }
}
