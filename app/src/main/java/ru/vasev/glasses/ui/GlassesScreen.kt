package ru.vasev.glasses.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
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
        GlassesPhoto(on = state.ready, modifier = Modifier.edgeToEdge(16.dp).semantics { contentDescription = described })
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
            // В узком сегменте «Симулятор очков» обрезался до «Очки» — короткое имя.
            Choices(state.photoSources.map { it to if (it == ImageSource.SIMULATOR) R.string.source_simulator_short else it.titleRes }, state.photoSource, onPhotoSource)
            Text(stringResource(R.string.glasses_photo_source_hint), style = MaterialTheme.typography.bodySmall, color = p.text2)
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
 * Фото очков (`drawable-nodpi/glasses_hero.webp`, верх и низ уходят в прозрачность) по всей ширине экрана.
 * Подключены — полная яркость и у объектива «дышит» мягкое белое свечение; нет — без цвета, полупрозрачно.
 * В светлой теме — на тёмной подложке: фон фото студийный, тёмный.
 */
@Composable
private fun GlassesPhoto(on: Boolean, modifier: Modifier) {
    val p = LocalSputnikColors.current
    val breath = rememberInfiniteTransition(label = "glow")
    val pulse by breath.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val glow = if (on) pulse else 0f
    val light = p.screen.luminance() > 0.5f
    Image(
        ImageBitmap.imageResource(R.drawable.glasses_hero),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        filterQuality = FilterQuality.High,
        alpha = if (on) 1f else OFF_ALPHA,
        colorFilter = if (on) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
        modifier = modifier
            .aspectRatio(PHOTO_WIDTH / PHOTO_HEIGHT)
            .then(if (light) Modifier.background(PHOTO_BACKING) else Modifier)
            .drawWithContent {
                drawContent()
                if (glow > 0f) {
                    // Объектив камеры на фото: центр и радиус кольца — доли ширины и высоты картинки.
                    val lens = Offset(size.width * LENS_X, size.height * LENS_Y)
                    val radius = size.width * LENS_RING * 2f
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = GLOW_ALPHA * glow), Color.Transparent), lens, radius), radius, lens)
                }
            },
    )
}

/** Выводит элемент из-под боковых отступов колонки: шире на [inset] с каждой стороны, от края до края экрана. */
private fun Modifier.edgeToEdge(inset: Dp) = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

private const val PHOTO_WIDTH = 1536f
private const val PHOTO_HEIGHT = 824f
private const val LENS_X = 0.3327f
private const val LENS_Y = 0.216f
private const val LENS_RING = 0.0241f
private const val GLOW_ALPHA = 0.5f
private const val OFF_ALPHA = 0.5f
private val PHOTO_BACKING = Color(0xFF0B0B0C)