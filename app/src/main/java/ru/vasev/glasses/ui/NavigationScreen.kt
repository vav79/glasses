package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.vasev.glasses.R
import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.nav.AddressConfirm
import ru.vasev.glasses.nav.AddressSource
import ru.vasev.glasses.nav.FoundAddress
import ru.vasev.glasses.nav.Geo
import ru.vasev.glasses.nav.GeoPoint
import ru.vasev.glasses.nav.MapsApp
import ru.vasev.glasses.nav.MapsLink
import ru.vasev.glasses.nav.NavBusy
import ru.vasev.glasses.nav.NavState
import ru.vasev.glasses.nav.NavigationViewModel
import ru.vasev.glasses.nav.Target
import ru.vasev.glasses.places.Destination
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.GlassShapes
import ru.vasev.glasses.ui.glass.glass
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassBackgroundText
import ru.vasev.glasses.ui.glass.glassContentSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Вкладка «Навигация»: «Куда?» (текст, голос, фото адреса), мои места, недавние цели; поверх — карточка цели,
 * экран моего места и подтверждение адреса. [onMic] — слушать «Куда?» (с разрешением на микрофон).
 */
@Composable
fun NavigationScreen(viewModel: NavigationViewModel, onMic: () -> Unit, onOpenLink: (MapsLink) -> Unit, onLaunchApp: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val compass by viewModel.compass.collectAsStateWithLifecycle()

    // Пока вкладка на экране — координаты свежие: расстояния в карточках меняются на ходу.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshLocation()
            delay(LOCATION_REFRESH_MS)
        }
    }
    LaunchedEffect(state.launch) {
        state.launch?.let {
            onLaunchApp(it)
            viewModel.launched()
        }
    }
    LaunchedEffect(state.open) {
        state.open?.let {
            onOpenLink(it)
            viewModel.linkOpened()
        }
    }

    Box(Modifier.fillMaxSize()) {
        compass?.let { c ->
            CompassScreen(viewModel, c, c.target.placeId?.let { id -> places.firstOrNull { it.id == id } }, diagnostics)
            return@Box
        }
        when {
            state.place != null -> PlaceScreen(viewModel, state, places, diagnostics, onMic)
            state.target != null -> TargetScreen(viewModel, state.target!!, state, places, diagnostics)
            else -> Home(viewModel, state, places, recent, diagnostics, onMic)
        }
        state.confirm?.let { ConfirmScreen(viewModel, it, state.here) }
        state.taxi?.let { card -> TaxiScreen(viewModel, card, card.target.placeId?.let { id -> places.firstOrNull { it.id == id } }, diagnostics) }
        state.offlineWalk?.let {
            AlertDialog(
                onDismissRequest = viewModel::dismissOfflineWalk,
                title = { Text(stringResource(R.string.nav_offline_title)) },
                text = {
                    Text(stringResource(if (viewModel.organicInstalled) R.string.nav_offline_organic else R.string.nav_offline_no_organic))
                },
                confirmButton = {
                    if (viewModel.organicInstalled) TextButton(onClick = viewModel::walkWithOrganic) { Text(stringResource(R.string.maps_organic)) }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissOfflineWalk) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun Home(
    viewModel: NavigationViewModel,
    state: NavState,
    places: List<Place>,
    recent: List<Destination>,
    diagnostics: Boolean,
    onMic: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .glassContentSource()
            .verticalScroll(rememberScrollState())
            .padding(top = LocalTabBarInset.current)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LocationStatus(state, diagnostics, viewModel::requestLocation)
        AddressSearch(viewModel, state, onMic, label = stringResource(R.string.nav_where_to))

        BackgroundTitle(stringResource(R.string.nav_my_places))
        val fixed = listOf(PlaceKind.HOTEL, PlaceKind.HOME, PlaceKind.CAR)
        val tiles = fixed.map { kind -> kind to places.firstOrNull { it.kind == kind } } +
            places.filter { it.kind == PlaceKind.CUSTOM }.map { it.kind to it }
        // Две колонки: плитки одной высоты в строке.
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (kind, place) ->
                    PlaceTile(viewModel, kind, place, state.here, Modifier.weight(1f)) {
                        if (place != null) viewModel.openPlaceTarget(place) else viewModel.newPlace(kind)
                    }
                }
                if (row.size == 1) AddPlaceTile(Modifier.weight(1f)) { viewModel.newPlace(PlaceKind.CUSTOM) }
            }
        }
        if (tiles.size % 2 == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AddPlaceTile(Modifier.weight(1f)) { viewModel.newPlace(PlaceKind.CUSTOM) }
                Box(Modifier.weight(1f))
            }
        }

        if (recent.isNotEmpty()) {
            BackgroundTitle(stringResource(R.string.nav_recent))
            recent.forEach { destination ->
                ListRow(
                    icon = R.drawable.ic_sym_history,
                    title = destination.label,
                    subtitle = listOfNotNull(
                        state.here?.let { distanceLabel(Geo.distanceM(it.point(), destination.point)) },
                        dateFormat().format(destination.usedAt.atZone(ZoneId.systemDefault())),
                    ).joinToString(" · "),
                    onClick = { viewModel.openRecent(destination) },
                )
            }
        }
    }
}

/** Поле «Куда?» с голосом и фото, состояние поиска, подсказки и кнопки «через ИИ», найденные адреса. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressSearch(viewModel: NavigationViewModel, state: NavState, onMic: () -> Unit, label: String) {
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    var choosePhoto by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.query,
        onValueChange = viewModel::setQuery,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_sym_search), contentDescription = null) },
        trailingIcon = {
            Row {
                IconButton(onClick = onMic) {
                    Icon(painterResource(R.drawable.ic_sym_mic), contentDescription = stringResource(R.string.nav_say_destination))
                }
                IconButton(onClick = { choosePhoto = true }) {
                    Icon(painterResource(R.drawable.ic_sym_photo_camera), contentDescription = stringResource(R.string.nav_photo_address))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        shape = GlassShapes.pill,
    )
    state.busy?.let { busy ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(busyLabel(busy)), style = MaterialTheme.typography.bodyMedium, color = glassBackgroundText(), modifier = Modifier.weight(1f))
            GlassButton(stringResource(R.string.cancel), onClick = viewModel::cancel)
        }
    }
    state.results.forEach { found ->
        ResultRow(found, state.here, onPick = { viewModel.pickResult(found) }, onMap = { viewModel.checkOnMap(found.point, found.line) })
    }
    if (state.notice != null || state.offerAiName || state.addressPhoto != null) {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.offerAiName) {
                    GlassButton(
                        stringResource(if (state.results.isEmpty()) R.string.nav_find_with_ai else R.string.nav_find_with_ai_other),
                        onClick = viewModel::findByNameWithAi,
                        icon = R.drawable.ic_sym_assistant,
                        enabled = aiReady && state.busy == null,
                    )
                }
                if (state.addressPhoto != null) {
                    GlassButton(
                        stringResource(R.string.nav_recognize_with_ai),
                        onClick = viewModel::recognizePhotoWithAi,
                        icon = R.drawable.ic_sym_assistant,
                        enabled = aiReady && state.busy == null,
                    )
                }
            }
            if (state.addressPhoto != null) Hint(stringResource(R.string.nav_ai_photo_hint))
            if (!aiReady && (state.offerAiName || state.addressPhoto != null)) Hint(stringResource(R.string.nav_ai_no_key))
        }
    }
    if (choosePhoto) {
        ModalBottomSheet(onDismissRequest = { choosePhoto = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nav_photo_address), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint(stringResource(R.string.nav_photo_address_hint))
                GlassButton(stringResource(R.string.nav_photo_take), onClick = {
                    choosePhoto = false
                    viewModel.addressFromPhoto(fromGallery = false)
                }, icon = R.drawable.ic_sym_photo_camera, modifier = Modifier.fillMaxWidth())
                GlassButton(stringResource(R.string.nav_photo_gallery), onClick = {
                    choosePhoto = false
                    viewModel.addressFromPhoto(fromGallery = true)
                }, icon = R.drawable.ic_sym_photo_library, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ResultRow(found: FoundAddress, here: DeviceLocation.Fix?, onPick: () -> Unit, onMap: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card)
            .clickable(role = Role.Button, onClick = onPick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(found.line, style = MaterialTheme.typography.bodyLarge)
            here?.let { Hint(distanceLabel(Geo.distanceM(it.point(), found.point))) }
        }
        GlassIconButton(R.drawable.ic_sym_map, stringResource(R.string.nav_check_on_map), onClick = onMap, size = 48.dp)
    }
}

/** Плитка моего места: сохранённое — расстояние и время; не сохранённое — пунктир и подсказка. */
@Composable
private fun PlaceTile(viewModel: NavigationViewModel, kind: PlaceKind, place: Place?, here: DeviceLocation.Fix?, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val title = place?.let(viewModel::placeLabel) ?: stringResource(NavigationViewModel.kindTitle(kind))
    val base = modifier.defaultMinSize(minHeight = 112.dp)
    Column(
        (if (place != null) base.glass(GlassShapes.card) else base.clip(GlassShapes.card).border(1.dp, c.outline, GlassShapes.card))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(kindIcon(kind)), contentDescription = null, tint = if (place != null) glassAccent() else c.onSurface, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (place == null) {
            Text(stringResource(R.string.nav_place_empty), style = MaterialTheme.typography.bodySmall, color = glassBackgroundText())
        } else {
            place.address?.let { Hint(it, maxLines = 1) }
            here?.let {
                val d = Geo.distanceM(it.point(), place.point)
                Text(distanceLabel(d), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (d < WALK_LABEL_MAX_M) Hint(stringResource(R.string.nav_walk_minutes, Geo.walkMinutes(d)))
            }
        }
    }
}

@Composable
private fun AddPlaceTile(modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier
            .defaultMinSize(minHeight = 112.dp)
            .clip(GlassShapes.card)
            .border(1.dp, c.outline, GlassShapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(R.drawable.ic_sym_add_location_alt), contentDescription = null, modifier = Modifier.size(26.dp))
        Text(stringResource(R.string.nav_add_place), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Карточка цели: расстояние, время пешком и варианты. Пешком дольше 20 минут — транспорт первым. */
@Composable
private fun TargetScreen(viewModel: NavigationViewModel, target: Target, state: NavState, places: List<Place>, diagnostics: Boolean) {
    BackHandler(onBack = viewModel::closeTarget)
    val distance = state.here?.let { Geo.distanceM(it.point(), target.point) }
    val transitFirst = distance != null && Geo.transitFirst(distance)
    val maps = stringResource(mapsTitle(viewModel.currentMapsApp()))
    val place = target.placeId?.let { id -> places.firstOrNull { it.id == id } }
    Column(
        Modifier
            .fillMaxSize()
            .glassContentSource()
            .verticalScroll(rememberScrollState())
            .padding(top = LocalTabBarInset.current)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocationStatus(state, diagnostics, viewModel::requestLocation)
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(R.drawable.ic_sym_arrow_back, stringResource(R.string.nav_back), onClick = viewModel::closeTarget, size = 48.dp)
                Text(
                    target.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 10.dp).semantics { heading() },
                )
            }
            target.address?.takeIf { it != target.label }?.let { Hint(it) }
            if (distance != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(distanceLabel(distance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    // Дальше 20 км время пешком бессмысленно («394 ч») — только «по прямой».
                    Text(
                        if (distance <= WALK_LABEL_MAX_M) stringResource(R.string.nav_straight_walk, walkTime(Geo.walkMinutes(distance)))
                        else stringResource(R.string.nav_straight_line),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
        if (transitFirst) BackgroundHint(stringResource(R.string.nav_transit_first))

        val compassRow: @Composable () -> Unit = {
            OptionRow(
                icon = R.drawable.ic_sym_navigation,
                title = stringResource(R.string.nav_compass),
                subtitle = stringResource(R.string.nav_compass_hint),
                onClick = viewModel::startCompass,
                external = false,
            )
        }
        val taxiRow: @Composable () -> Unit = {
            OptionRow(
                icon = R.drawable.ic_sym_local_taxi,
                title = stringResource(R.string.nav_taxi),
                subtitle = stringResource(R.string.nav_taxi_hint),
                onClick = viewModel::openTaxi,
                external = false,
            )
        }
        // Близко (до 1,5 км по прямой) — «Компас» первым среди пеших вариантов.
        val nearby = distance != null && Geo.compassSuitable(distance)
        val walk: @Composable () -> Unit = {
            OptionRow(
                icon = R.drawable.ic_sym_directions_walk,
                title = stringResource(R.string.nav_walk),
                subtitle = stringResource(R.string.nav_walk_in_app, maps),
                onClick = viewModel::walk,
            )
        }
        val transit: @Composable () -> Unit = {
            OptionRow(
                icon = R.drawable.ic_sym_directions_bus,
                title = stringResource(R.string.nav_transit),
                subtitle = stringResource(R.string.nav_transit_in_app, maps),
                onClick = viewModel::transit,
            )
        }
        if (transitFirst) {
            transit()
            taxiRow()
            if (nearby) compassRow()
            walk()
        } else {
            if (nearby) compassRow()
            walk()
            transit()
            taxiRow()
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(stringResource(R.string.nav_check_on_map), onClick = { viewModel.checkOnMap(target.point, target.label) }, icon = R.drawable.ic_sym_map)
            if (place != null) {
                GlassButton(stringResource(R.string.nav_edit_place), onClick = { viewModel.openPlace(place) }, icon = kindIcon(place.kind))
            } else {
                GlassButton(stringResource(R.string.nav_save_to_places), onClick = viewModel::saveTargetAsPlace, icon = R.drawable.ic_sym_add_location_alt)
            }
        }
    }
}

@Composable
private fun OptionRow(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit, external: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .glass(GlassShapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = glassAccent(), modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Hint(subtitle)
        }
        if (external) Icon(painterResource(R.drawable.ic_sym_open_in_new), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** Моё место: фото входа, когда и как сохранено, адрес; сохранить здесь, по адресу или по фото адреса. */
@Composable
private fun PlaceScreen(viewModel: NavigationViewModel, state: NavState, places: List<Place>, diagnostics: Boolean, onMic: () -> Unit) {
    BackHandler(onBack = viewModel::closePlace)
    val edit = state.place ?: return
    val place = edit.placeId?.let { id -> places.firstOrNull { it.id == id } }
    var confirmDelete by remember { mutableStateOf(false) }
    val photo by produceState<Bitmap?>(null, place?.id, place?.hasPhoto, place?.savedAt) { value = place?.let { viewModel.placePhoto(it, PHOTO_SIDE) } }
    val busy = state.busy != null

    // Непрозрачная подложка, как у «Заметок»: под местом не должна просвечивать вкладка.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTabBarInset.current)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(R.drawable.ic_sym_arrow_back, stringResource(R.string.nav_back), onClick = viewModel::closePlace, size = 48.dp)
                Text(
                    place?.let(viewModel::placeLabel) ?: stringResource(NavigationViewModel.kindTitle(edit.kind)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 10.dp).semantics { heading() },
                )
                if (place != null) GlassIconButton(R.drawable.ic_sym_delete, stringResource(R.string.nav_delete_place), onClick = { confirmDelete = true }, size = 48.dp)
            }
            if (confirmDelete) {
                GlassCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.errorContainer, text = MaterialTheme.colorScheme.onErrorContainer) {
                    Text(stringResource(R.string.nav_delete_place_confirm), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(stringResource(R.string.delete), onClick = { confirmDelete = false; viewModel.deletePlace() }, kind = GlassButtonKind.ERROR)
                        GlassButton(stringResource(R.string.cancel), onClick = { confirmDelete = false })
                    }
                }
            }
            if (edit.kind == PlaceKind.CUSTOM) {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = viewModel::setPlaceName,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.nav_place_name)) },
                    shape = GlassShapes.pill,
                )
            }

            photo?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.nav_entrance_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(PHOTO_RATIO).clip(RoundedCornerShape(24.dp)),
                )
            }

            if (place != null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Label(stringResource(R.string.nav_saved_label))
                    val how = when {
                        place.accuracyM == null -> stringResource(R.string.nav_saved_by_address)
                        diagnostics || place.accuracyM > WEAK_GPS_M -> stringResource(R.string.nav_saved_by_gps_accuracy, place.accuracyM.toInt())
                        else -> stringResource(R.string.nav_saved_by_gps)
                    }
                    Text("${savedFormat().format(place.savedAt.atZone(ZoneId.systemDefault()))} · $how", style = MaterialTheme.typography.bodyLarge)
                    place.address?.let {
                        Label(stringResource(R.string.nav_address_label))
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (!place.hasPhoto) Hint(stringResource(R.string.nav_no_entrance_photo))
                }
            } else {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nav_place_new_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }

            GlassButton(
                stringResource(if (place == null) R.string.nav_save_here else R.string.nav_save_here_again),
                onClick = viewModel::savePlaceHere,
                icon = R.drawable.ic_sym_my_location,
                kind = if (place == null) GlassButtonKind.PRIMARY else GlassButtonKind.NORMAL,
                enabled = !busy && (edit.kind != PlaceKind.CUSTOM || edit.name.isNotBlank() || place != null),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.nav_save_here_hint))
            if (place != null) {
                GlassButton(
                    stringResource(if (place.hasPhoto) R.string.nav_retake_photo else R.string.nav_take_photo),
                    onClick = viewModel::retakePlacePhoto,
                    icon = R.drawable.ic_sym_photo_camera,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            BackgroundTitle(stringResource(R.string.nav_place_by_address))
            AddressSearch(viewModel, state, onMic, label = stringResource(R.string.nav_address_field))

            if (place != null) {
                GlassButton(
                    stringResource(R.string.nav_go_here),
                    onClick = {
                        viewModel.closePlace()
                        viewModel.openPlaceTarget(place)
                    },
                    icon = R.drawable.ic_sym_navigation,
                    kind = GlassButtonKind.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassButton(
                    stringResource(R.string.nav_address_for_driver),
                    onClick = {
                        viewModel.closePlace()
                        viewModel.openPlaceTarget(place)
                        viewModel.openTaxi()
                    },
                    icon = R.drawable.ic_sym_local_taxi,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Подтверждение адреса: что назвала модель или распознал телефон, что нашёл геокодер, и «Проверить на карте». */
@Composable
private fun ConfirmScreen(viewModel: NavigationViewModel, confirm: AddressConfirm, here: DeviceLocation.Fix?) {
    BackHandler(onBack = viewModel::dismissConfirm)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTabBarInset.current)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(R.drawable.ic_sym_arrow_back, stringResource(R.string.nav_back), onClick = viewModel::dismissConfirm, size = 48.dp)
                Text(
                    stringResource(if (confirm.source == AddressSource.NAME_AI) R.string.nav_confirm_by_name else R.string.nav_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 10.dp).semantics { heading() },
                )
            }
            GlassCard(Modifier.fillMaxWidth()) {
                confirm.aiText?.let { text ->
                    Label(
                        stringResource(
                            when (confirm.source) {
                                AddressSource.NAME_AI -> R.string.nav_confirm_from_ai_name
                                AddressSource.PHOTO_AI -> R.string.nav_confirm_from_ai_photo
                                else -> R.string.nav_confirm_from_device
                            },
                        ),
                    )
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    confirm.tag?.takeIf(viewModel::showTag)?.let { Hint(modelLabel(it)) }
                }
                Label(stringResource(R.string.nav_confirm_geocoder))
                Text(confirm.found.line, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                here?.let { Hint(distanceLabel(Geo.distanceM(it.point(), confirm.found.point))) }
            }
            GlassCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.secondaryContainer, text = MaterialTheme.colorScheme.onSecondaryContainer) {
                Text(stringResource(R.string.nav_confirm_check), style = MaterialTheme.typography.bodyMedium)
            }
            GlassButton(
                stringResource(R.string.nav_check_on_map),
                onClick = { viewModel.checkOnMap(confirm.found.point, confirm.found.line) },
                icon = R.drawable.ic_sym_map,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(stringResource(R.string.nav_confirm_ok), onClick = viewModel::acceptConfirm, kind = GlassButtonKind.PRIMARY, modifier = Modifier.weight(1f))
                GlassButton(stringResource(R.string.nav_confirm_fix), onClick = viewModel::dismissConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Нет разрешения — кнопка; слабый GPS — предупреждение; точность и прочее служебное — только разработчику. */
@Composable
private fun LocationStatus(state: NavState, diagnostics: Boolean, onRequest: () -> Unit) {
    val c = MaterialTheme.colorScheme
    when {
        !state.locationGranted -> GlassCard(Modifier.fillMaxWidth(), tint = c.secondaryContainer, text = c.onSecondaryContainer) {
            Text(stringResource(R.string.nav_location_needed), style = MaterialTheme.typography.bodyMedium)
            GlassButton(stringResource(R.string.nav_allow_location), onClick = onRequest, kind = GlassButtonKind.PRIMARY)
        }
        state.here?.accuracyM?.let { it > WEAK_GPS_M } == true -> GlassCard(Modifier.fillMaxWidth(), tint = c.errorContainer, text = c.onErrorContainer) {
            Text(stringResource(R.string.nav_weak_gps, state.here.accuracyM!!.toInt()), style = MaterialTheme.typography.bodyMedium)
        }
        diagnostics -> BackgroundHint(
            state.here?.accuracyM?.let { stringResource(R.string.nav_gps_accuracy, it.toInt()) } ?: stringResource(R.string.nav_gps_waiting),
        )
    }
}

@Composable
private fun ListRow(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .glass(GlassShapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Hint(subtitle)
        }
    }
}

@Composable
private fun BackgroundTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = glassBackgroundText(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
    )
}

@Composable
private fun BackgroundHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = glassBackgroundText(), modifier = Modifier.padding(horizontal = 4.dp))
}

/** Пояснение внутри стеклянной карточки: второстепенный цвет, подтемнённый до контраста 4.5:1. */
@Composable
private fun Hint(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
private fun distanceLabel(meters: Double): String {
    val d = Geo.distanceText(meters, LocalConfiguration.current.locales[0])
    return stringResource(if (d.km) R.string.nav_km else R.string.nav_m, d.value)
}

@Composable
private fun walkTime(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.nav_minutes, minutes) else stringResource(R.string.nav_hours_minutes, minutes / 60, minutes % 60)

private fun busyLabel(busy: NavBusy): Int = when (busy) {
    NavBusy.LISTENING -> R.string.nav_busy_listening
    NavBusy.SEARCHING -> R.string.nav_busy_searching
    NavBusy.CAPTURING -> R.string.nav_busy_capturing
    NavBusy.RECOGNIZING -> R.string.nav_busy_recognizing
    NavBusy.ASKING_AI -> R.string.nav_busy_ai
    NavBusy.LOCATING -> R.string.nav_busy_locating
}

@DrawableRes
private fun kindIcon(kind: PlaceKind): Int = when (kind) {
    PlaceKind.HOTEL -> R.drawable.ic_sym_hotel
    PlaceKind.HOME -> R.drawable.ic_sym_home
    PlaceKind.CAR -> R.drawable.ic_sym_directions_car
    PlaceKind.CUSTOM -> R.drawable.ic_sym_bookmark
}

private fun mapsTitle(app: MapsApp): Int = when (app) {
    MapsApp.GOOGLE -> R.string.maps_google
    MapsApp.YANDEX -> R.string.maps_yandex
    MapsApp.ORGANIC -> R.string.maps_organic
    MapsApp.GEO -> R.string.maps_other
}

private fun DeviceLocation.Fix.point() = GeoPoint(latitude, longitude)

private const val LOCATION_REFRESH_MS = 10_000L
private const val WEAK_GPS_M = 50f
private const val WALK_LABEL_MAX_M = 20_000.0
private const val PHOTO_SIDE = 1024
private const val PHOTO_RATIO = 4f / 3f

@Composable
private fun dateFormat(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
}

@Composable
private fun savedFormat(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofPattern("d MMM, HH:mm", locale) }
}
