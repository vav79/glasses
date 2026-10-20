package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.nav.CompassController
import ru.vasev.glasses.nav.CompassState
import ru.vasev.glasses.nav.Geo
import ru.vasev.glasses.nav.HeadingKind
import ru.vasev.glasses.nav.NavigationViewModel
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassBackgroundText
import java.text.DateFormat
import java.util.Date

/**
 * «Компас»: большая стрелка (куда повернуть относительно взгляда; взгляд неизвестен — на цель от севера),
 * расстояние по прямой, время и что сказано голосом. Закрытие — «Назад» или крестик: компас выключается.
 */
@Composable
fun CompassScreen(viewModel: NavigationViewModel, state: CompassState, place: Place?, diagnostics: Boolean) {
    BackHandler(onBack = viewModel::closeCompass)
    val c = MaterialTheme.colorScheme
    val photo by produceState<Bitmap?>(null, place?.id, place?.hasPhoto) { value = place?.let { viewModel.placePhoto(it, 512) } }
    val sideText = when {
        state.side != null -> stringResource(CompassController.sideText(state.side)).replaceFirstChar(Char::uppercase)
        state.cardinal != null -> stringResource(CompassController.cardinalText(state.cardinal)).replaceFirstChar(Char::uppercase)
        else -> null
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTabBarInset.current)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(R.drawable.ic_sym_close, stringResource(R.string.compass_stop), onClick = viewModel::closeCompass, size = 48.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(state.target.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.compass_offline_hint), style = MaterialTheme.typography.bodySmall, color = glassBackgroundText())
                }
                photo?.let {
                    Image(it.asImageBitmap(), stringResource(R.string.nav_entrance_photo), contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                }
            }

            when {
                state.arrived -> Arrived(state, photo)
                state.autoStopped -> GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.compass_auto_stop), style = MaterialTheme.typography.bodyLarge)
                }
                state.distanceM == null -> GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nav_gps_waiting), style = MaterialTheme.typography.bodyLarge)
                }
                else -> {
                    Arrow(state, sideText)
                    val d = Geo.distanceText(state.distanceM, LocalConfiguration.current.locales[0])
                    Text(stringResource(if (d.km) R.string.nav_km else R.string.nav_m, d.value), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.compass_straight_minutes, Geo.walkMinutes(state.distanceM)),
                        style = MaterialTheme.typography.titleMedium,
                        color = glassBackgroundText(),
                    )
                    sideText?.let { Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = glassAccent(tint = c.surface)) }
                    if (state.heading == null) {
                        Text(stringResource(R.string.compass_no_heading), style = MaterialTheme.typography.bodySmall, color = glassBackgroundText(), textAlign = TextAlign.Center)
                    }
                }
            }

            if (state.stuck) {
                GlassCard(Modifier.fillMaxWidth(), tint = c.errorContainer, text = c.onErrorContainer) {
                    Text(stringResource(R.string.compass_stuck), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(stringResource(R.string.compass_open_navigator), onClick = viewModel::compassToNavigator, kind = GlassButtonKind.PRIMARY)
                        GlassButton(stringResource(R.string.compass_continue), onClick = viewModel::compassContinue)
                    }
                }
            }

            state.lastSpoken?.let { text ->
                GlassCard(Modifier.fillMaxWidth()) {
                    val at = state.lastSpokenAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                    Text(stringResource(R.string.compass_said, at.orEmpty()), style = MaterialTheme.typography.labelMedium, color = glassAccent(accent = c.onSurfaceVariant))
                    Text("«$text»", style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Служебное — только разработчику; слабый GPS — всем.
            val weak = (state.accuracyM ?: 0f) > WEAK_GPS_M
            if (diagnostics || weak) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accuracyM?.let { Chip(stringResource(if (weak) R.string.nav_weak_gps else R.string.nav_gps_accuracy, it.toInt())) }
                    if (diagnostics) {
                        Chip(
                            stringResource(
                                when (state.heading) {
                                    HeadingKind.PHONE_COMPASS -> R.string.compass_heading_phone
                                    HeadingKind.WALKING_DIRECTION -> R.string.compass_heading_walking
                                    null -> R.string.compass_heading_none
                                },
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.size(4.dp))
            if (state.arrived || state.autoStopped) {
                GlassButton(stringResource(R.string.compass_done), onClick = viewModel::closeCompass, kind = GlassButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        stringResource(R.string.compass_how_far),
                        onClick = viewModel::compassStatus,
                        icon = R.drawable.ic_sym_volume_up,
                        kind = GlassButtonKind.PRIMARY,
                        enabled = state.distanceM != null,
                        modifier = Modifier.weight(1f),
                    )
                    GlassButton(stringResource(R.string.compass_navigator), onClick = viewModel::compassToNavigator)
                }
            }
        }
    }
}

/** Стрелка: поворот по кратчайшему пути (с 350° на 10° — через север, а не через полный круг). */
@Composable
private fun Arrow(state: CompassState, description: String?) {
    val target = (state.relativeDeg ?: state.bearingDeg ?: 0.0).toFloat()
    val unwrapped = remember { mutableFloatStateOf(target) }
    var delta = (target - unwrapped.floatValue) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    unwrapped.floatValue += delta
    val angle by animateFloatAsState(unwrapped.floatValue, tween(300), label = "arrow")
    Box(
        Modifier
            .size(260.dp)
            .glass(GlassShapes.pill)
            .semantics { contentDescription = description.orEmpty() },
        contentAlignment = Alignment.Center,
    ) {
        if (state.relativeDeg == null) {
            // Взгляд неизвестен: стрелка — от севера, север — сверху.
            Text(stringResource(R.string.compass_north_short), style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        } else {
            Text(stringResource(R.string.compass_forward), style = MaterialTheme.typography.labelMedium, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant), modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        }
        Icon(painterResource(R.drawable.ic_sym_navigation), contentDescription = null, tint = glassAccent(), modifier = Modifier.size(170.dp).rotate(angle))
    }
}

@Composable
private fun Arrived(state: CompassState, photo: Bitmap?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.compass_arrived_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.compass_arrived_hint, state.target.label), style = MaterialTheme.typography.titleMedium, color = glassBackgroundText(), textAlign = TextAlign.Center)
        photo?.let {
            Image(it.asImageBitmap(), stringResource(R.string.nav_entrance_photo), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().size(220.dp).clip(RoundedCornerShape(24.dp)))
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.glass(GlassShapes.pill).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val WEAK_GPS_M = 100f
