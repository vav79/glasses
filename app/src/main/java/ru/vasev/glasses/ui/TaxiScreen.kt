package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.vasev.glasses.R
import ru.vasev.glasses.nav.NavigationViewModel
import ru.vasev.glasses.nav.TaxiAddresses
import ru.vasev.glasses.nav.TaxiApp
import ru.vasev.glasses.nav.TaxiCard
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassIconButton

/**
 * «Покажите водителю»: адрес на местном языке крупно, на языке приложения мелко, Plus Code, фото входа
 * и установленные приложения такси. Непрозрачный, как полноэкранный перевод: читают в машине и на солнце.
 */
@Composable
fun TaxiScreen(viewModel: NavigationViewModel, card: TaxiCard, place: Place?, diagnostics: Boolean) {
    BackHandler(onBack = viewModel::closeTaxi)
    val c = MaterialTheme.colorScheme
    val photo by produceState<Bitmap?>(null, place?.id, place?.hasPhoto) { value = place?.let { viewModel.placePhoto(it, 1024) } }
    val local = card.local
    val localeList = local?.let { LocaleList(it.language) }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = LocalTabBarInset.current).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(R.drawable.ic_sym_close, stringResource(R.string.taxi_close), onClick = viewModel::closeTaxi, size = 48.dp)
                Text(
                    stringResource(R.string.taxi_show_driver),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { heading() },
                )
                if (local != null) {
                    GlassIconButton(R.drawable.ic_sym_volume_up, stringResource(R.string.taxi_speak), onClick = viewModel::speakTaxi, size = 48.dp)
                }
            }

            when {
                local != null && !viewModel.sameAsApp(local.language) -> {
                    local.name?.let {
                        Text(it, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = c.onSurface, style = MaterialTheme.typography.headlineLarge.copy(localeList = localeList))
                    }
                    Text(local.address, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = c.onSurface, style = MaterialTheme.typography.headlineMedium.copy(localeList = localeList))
                }
                else -> Text(card.target.address ?: card.target.label, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = c.onSurface)
            }
            Text(stringResource(R.string.taxi_plus_code, card.plusCode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVariant)

            HorizontalDivider()
            Text(card.target.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            card.target.address?.takeIf { it != card.target.label }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant) }

            // Состояние перевода.
            val status = when {
                card.loading -> stringResource(R.string.taxi_translating)
                local != null && local.by != null && (diagnostics || local.by != TaxiAddresses.MAIN_MODEL) ->
                    stringResource(R.string.taxi_translated_by, if (local.by == TaxiAddresses.MAIN_MODEL) stringResource(R.string.taxi_main_model) else local.by)
                local != null && place != null -> stringResource(R.string.taxi_saved_offline)
                card.offline -> stringResource(R.string.taxi_offline)
                local == null && !card.homeConsent -> stringResource(R.string.taxi_not_translated)
                else -> null
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
            if (card.homeConsent) {
                Text(stringResource(R.string.taxi_home_consent), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                GlassButton(stringResource(R.string.taxi_translate_home), onClick = viewModel::translateHomeWithAi, icon = R.drawable.ic_sym_translate)
            }

            photo?.let {
                Image(
                    it.asImageBitmap(),
                    stringResource(R.string.nav_entrance_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp)),
                )
            }

            if (card.apps.isEmpty()) {
                Text(stringResource(R.string.taxi_no_apps), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.apps.forEachIndexed { index, app ->
                        GlassButton(
                            stringResource(R.string.taxi_open_app, appTitle(app)),
                            onClick = { viewModel.openTaxiApp(app) },
                            kind = if (index == 0) GlassButtonKind.PRIMARY else GlassButtonKind.NORMAL,
                            icon = R.drawable.ic_sym_local_taxi,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun appTitle(app: TaxiApp): String = if (app == TaxiApp.YANDEX_GO) stringResource(R.string.taxi_yandex_go) else app.title
