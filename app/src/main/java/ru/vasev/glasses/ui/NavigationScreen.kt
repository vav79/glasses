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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import ru.vasev.glasses.llm.ModelTag
import ru.vasev.glasses.nav.AddressSearchViewModel
import ru.vasev.glasses.nav.NavBusy
import ru.vasev.glasses.nav.NavSession
import ru.vasev.glasses.nav.PhotoCapture
import ru.vasev.glasses.nav.PlaceEdit
import ru.vasev.glasses.nav.PlacesViewModel
import ru.vasev.glasses.nav.Target
import ru.vasev.glasses.nav.TargetViewModel
import ru.vasev.glasses.nav.TaxiViewModel
import ru.vasev.glasses.nav.TransitState
import ru.vasev.glasses.nav.ViewfinderRequest
import ru.vasev.glasses.nav.kindTitle
import ru.vasev.glasses.nav.placeLabel
import ru.vasev.glasses.links.LinkOpener
import androidx.compose.ui.platform.LocalContext
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

/** ViewModel экранов «Навигации» и их общее состояние ([NavSession]: что открыто, где я, подсказка, текущее действие). */
class NavViewModels(
    val session: NavSession,
    val search: AddressSearchViewModel,
    val target: TargetViewModel,
    val taxi: TaxiViewModel,
    val places: PlacesViewModel,
)

/**
 * Вкладка «Навигация»: «Куда?» (текст, голос, фото адреса), мои места, недавние цели; поверх — карточка цели,
 * экран моего места и подтверждение адреса. [onMic] — слушать «Куда?» (с разрешением на микрофон).
 */
@Composable
fun NavigationScreen(vms: NavViewModels, onMic: () -> Unit, onOpenLink: (MapsLink) -> Unit, onLaunchApp: (String) -> Unit) {
    val session = vms.session
    val search by vms.search.state.collectAsStateWithLifecycle()
    val target by session.target.collectAsStateWithLifecycle()
    val edit by session.place.collectAsStateWithLifecycle()
    val launch by session.launch.collectAsStateWithLifecycle()
    val open by session.open.collectAsStateWithLifecycle()
    val places by vms.places.places.collectAsStateWithLifecycle()
    val diagnostics by session.diagnostics.collectAsStateWithLifecycle()
    val compass by vms.target.compass.collectAsStateWithLifecycle()
    val taxiCard by vms.taxi.card.collectAsStateWithLifecycle()
    val viewfinder by session.photos.viewfinder.collectAsStateWithLifecycle()
    val offlineWalk by vms.target.offlineWalk.collectAsStateWithLifecycle()

    // Пока вкладка на экране — координаты свежие: расстояния в карточках меняются на ходу.
    LaunchedEffect(Unit) {
        while (true) {
            session.refreshLocation()
            delay(LOCATION_REFRESH_MS)
        }
    }
    LaunchedEffect(launch) {
        launch?.let {
            onLaunchApp(it)
            session.launched()
        }
    }
    LaunchedEffect(open) {
        open?.let {
            onOpenLink(it)
            session.linkOpened()
        }
    }

    Box(Modifier.fillMaxSize()) {
        compass?.let { c ->
            CompassScreen(vms.target, c, c.target.placeId?.let { id -> places.firstOrNull { it.id == id } }, diagnostics)
            return@Box
        }
        // Подтверждение адреса заменяет экран под ним (а не лежит поверх) — фон везде один стеклянный.
        val confirm = search.confirm
        val placeEdit = edit
        val shownTarget = target
        when {
            confirm != null -> ConfirmScreen(vms, confirm)
            placeEdit != null -> PlaceScreen(vms, placeEdit, places, diagnostics, onMic)
            shownTarget != null -> TargetScreen(vms, shownTarget, places, diagnostics)
            else -> Home(vms, places, diagnostics, onMic)
        }
        taxiCard?.let { card -> TaxiScreen(vms.taxi, card, card.target.placeId?.let { id -> places.firstOrNull { it.id == id } }, diagnostics) }
        viewfinder?.let { NavViewfinder(session.photos, it) }
        offlineWalk?.let {
            val organic = vms.target.organicInstalled
            AlertDialog(
                onDismissRequest = vms.target::dismissOfflineWalk,
                title = { Text(stringResource(R.string.nav_offline_title)) },
                text = {
                    Text(stringResource(if (organic) R.string.nav_offline_organic else R.string.nav_offline_no_organic))
                },
                confirmButton = {
                    if (organic) TextButton(onClick = vms.target::walkWithOrganic) { Text(stringResource(R.string.maps_organic)) }
                },
                dismissButton = { TextButton(onClick = vms.target::dismissOfflineWalk) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun Home(
    vms: NavViewModels,
    places: List<Place>,
    diagnostics: Boolean,
    onMic: () -> Unit,
) {
    val viewModel = vms.places
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val here by vms.session.here.collectAsStateWithLifecycle()
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
        LocationStatus(vms.session, diagnostics)
        AddressSearch(vms, onMic, label = stringResource(R.string.nav_where_to))

        BackgroundTitle(stringResource(R.string.nav_my_places))
        val fixed = listOf(PlaceKind.HOTEL, PlaceKind.HOME, PlaceKind.CAR)
        val tiles = fixed.map { kind -> kind to places.firstOrNull { it.kind == kind } } +
            places.filter { it.kind == PlaceKind.CUSTOM }.map { it.kind to it }
        // Две колонки: плитки одной высоты в строке.
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (kind, place) ->
                    PlaceTile(kind, place, here, Modifier.weight(1f)) {
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
                        here?.let { distanceLabel(Geo.distanceM(it.point(), destination.point)) },
                        dateFormat().format(destination.usedAt.atZone(ZoneId.systemDefault())),
                    ).joinToString(" · "),
                    onClick = { viewModel.openRecent(destination) },
                )
            }
        }

        if (places.isNotEmpty() || recent.isNotEmpty()) {
            var confirmClear by remember { mutableStateOf(false) }
            GlassButton(
                stringResource(R.string.nav_clear_all),
                onClick = { confirmClear = true },
                icon = R.drawable.ic_sym_delete,
                modifier = Modifier.fillMaxWidth(),
            )
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(stringResource(R.string.nav_clear_all)) },
                    text = { Text(stringResource(R.string.nav_clear_all_confirm)) },
                    confirmButton = {
                        TextButton(onClick = { confirmClear = false; viewModel.clearAll() }) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}

/** Видоискатель камеры телефона для фото входа или адреса: снимок — по затвору, камера работает, пока он на экране. */
@Composable
private fun NavViewfinder(viewModel: PhotoCapture, request: ViewfinderRequest) {
    BackHandler(onBack = viewModel::viewfinderBack)
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PhoneViewfinderFull(viewModel.phoneCamera)
        // Затемнение сверху и снизу: кнопки и подсказка читаются на любом кадре.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(LocalTabBarInset.current + 96.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
        Row(
            Modifier.fillMaxWidth().padding(top = LocalTabBarInset.current).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                R.drawable.ic_sym_arrow_back,
                stringResource(R.string.nav_back),
                onClick = viewModel::viewfinderBack,
                size = 48.dp,
                modifier = Modifier.background(c.surface.copy(alpha = CONTROL_ALPHA), CircleShape),
            )
            Text(
                stringResource(request.hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (request.allowSkip) {
                GlassButton(
                    stringResource(R.string.nav_viewfinder_skip),
                    onClick = viewModel::viewfinderSkip,
                    enabled = !request.shooting,
                    modifier = Modifier.background(c.surface.copy(alpha = CONTROL_ALPHA), GlassShapes.pill),
                )
            }
            GlassIconButton(
                R.drawable.ic_sym_photo_camera,
                stringResource(R.string.nav_viewfinder_shoot),
                onClick = viewModel::viewfinderShoot,
                size = 72.dp,
                enabled = !request.shooting,
                kind = GlassButtonKind.PRIMARY,
                modifier = Modifier.background(c.primaryContainer.copy(alpha = CONTROL_ALPHA), CircleShape),
            )
        }
    }
}

/** Подложка кнопок на кадре камеры: стекло рассчитано на градиент, а не на снимок любой яркости. */
private const val CONTROL_ALPHA = 0.85f

/** Поле «Куда?» с голосом и фото, состояние поиска, подсказки и кнопки «через ИИ», найденные адреса. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressSearch(vms: NavViewModels, onMic: () -> Unit, label: String) {
    val viewModel = vms.search
    val state by viewModel.state.collectAsStateWithLifecycle()
    val aiReady by vms.session.aiReady.collectAsStateWithLifecycle()
    val busy by vms.session.busy.collectAsStateWithLifecycle()
    val notice by vms.session.notice.collectAsStateWithLifecycle()
    val here by vms.session.here.collectAsStateWithLifecycle()
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
    busy?.let { task ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(busyLabel(task)), style = MaterialTheme.typography.bodyMedium, color = glassBackgroundText(), modifier = Modifier.weight(1f))
            GlassButton(stringResource(R.string.cancel), onClick = viewModel::cancel)
        }
    }
    state.results.forEach { found ->
        ResultRow(found, here, onPick = { viewModel.pickResult(found) }, onMap = { viewModel.checkOnMap(found.point, found.line) })
    }
    if (notice != null || state.offerAiName || state.addressPhoto != null) {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.offerAiName) {
                    GlassButton(
                        stringResource(if (state.results.isEmpty()) R.string.nav_find_with_ai else R.string.nav_find_with_ai_other),
                        onClick = viewModel::findByNameWithAi,
                        icon = R.drawable.ic_sym_assistant,
                        enabled = aiReady && busy == null,
                    )
                }
                if (state.addressPhoto != null) {
                    GlassButton(
                        stringResource(R.string.nav_recognize_with_ai),
                        onClick = viewModel::recognizePhotoWithAi,
                        icon = R.drawable.ic_sym_assistant,
                        enabled = aiReady && busy == null,
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
private fun PlaceTile(kind: PlaceKind, place: Place?, here: DeviceLocation.Fix?, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val title = place?.let(::placeLabel) ?: stringResource(kindTitle(kind))
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
private fun TargetScreen(vms: NavViewModels, target: Target, places: List<Place>, diagnostics: Boolean) {
    val viewModel = vms.target
    val here by vms.session.here.collectAsStateWithLifecycle()
    val transitState by viewModel.transit.collectAsStateWithLifecycle()
    BackHandler(onBack = viewModel::closeTarget)
    val distance = here?.let { Geo.distanceM(it.point(), target.point) }
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
        LocationStatus(vms.session, diagnostics)
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
            TransitTipsCard(viewModel, transitState, diagnostics, vms.session::showTag)
        }
        // Пешком дольше 20 минут, но в пределах города — советы по транспорту загружаются сами (если есть сеть и ключ);
        // дальше (другой город, страна) — только по кнопке: это уже не городской транспорт, а запрос стоит денег.
        val autoTips = transitFirst && distance != null && distance <= AUTO_TIPS_MAX_M
        LaunchedEffect(target, autoTips) { if (autoTips) viewModel.loadTransitTips(auto = true) }
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
private fun PlaceScreen(vms: NavViewModels, edit: PlaceEdit, places: List<Place>, diagnostics: Boolean, onMic: () -> Unit) {
    val viewModel = vms.places
    BackHandler(onBack = viewModel::closePlace)
    val place = edit.placeId?.let { id -> places.firstOrNull { it.id == id } }
    var confirmDelete by remember { mutableStateOf(false) }
    val photo by produceState<Bitmap?>(null, place?.id, place?.hasPhoto, place?.savedAt) { value = place?.let { viewModel.placePhoto(it, PHOTO_SIDE) } }
    val busy = vms.session.busy.collectAsStateWithLifecycle().value != null

    // Экран места заменяет главную вкладки (а не лежит поверх) — фон тот же стеклянный.
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .glassContentSource()
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
                    place?.let(::placeLabel) ?: stringResource(kindTitle(edit.kind)),
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
            AddressSearch(vms, onMic, label = stringResource(R.string.nav_address_field))

            if (place != null) {
                GlassButton(
                    stringResource(R.string.nav_go_here),
                    onClick = { viewModel.goToPlace(place) },
                    icon = R.drawable.ic_sym_navigation,
                    kind = GlassButtonKind.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassButton(
                    stringResource(R.string.nav_address_for_driver),
                    onClick = { viewModel.taxiForPlace(place) },
                    icon = R.drawable.ic_sym_local_taxi,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Подтверждение адреса: что назвала модель или распознал телефон, что нашёл геокодер, и «Проверить на карте». */
@Composable
private fun ConfirmScreen(vms: NavViewModels, confirm: AddressConfirm) {
    val viewModel = vms.search
    val here by vms.session.here.collectAsStateWithLifecycle()
    BackHandler(onBack = viewModel::dismissConfirm)
    Box(Modifier.fillMaxSize()) {
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
                    confirm.tag?.takeIf(vms.session::showTag)?.let { Hint(modelLabel(it)) }
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
private fun LocationStatus(session: NavSession, diagnostics: Boolean) {
    val c = MaterialTheme.colorScheme
    val granted by session.locationGranted.collectAsStateWithLifecycle()
    val here by session.here.collectAsStateWithLifecycle()
    val accuracy = here?.accuracyM
    when {
        !granted -> GlassCard(Modifier.fillMaxWidth(), tint = c.secondaryContainer, text = c.onSecondaryContainer) {
            Text(stringResource(R.string.nav_location_needed), style = MaterialTheme.typography.bodyMedium)
            GlassButton(stringResource(R.string.nav_allow_location), onClick = session::requestLocation, kind = GlassButtonKind.PRIMARY)
        }
        accuracy != null && accuracy > WEAK_GPS_M -> GlassCard(Modifier.fillMaxWidth(), tint = c.errorContainer, text = c.onErrorContainer) {
            Text(stringResource(R.string.nav_weak_gps, accuracy.toInt()), style = MaterialTheme.typography.bodyMedium)
        }
        diagnostics -> BackgroundHint(
            accuracy?.let { stringResource(R.string.nav_gps_accuracy, it.toInt()) } ?: stringResource(R.string.nav_gps_waiting),
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
private const val WEAK_GPS_M = 100f
private const val WALK_LABEL_MAX_M = 20_000.0
private const val AUTO_TIPS_MAX_M = 50_000.0
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

/**
 * Советы по транспорту: строки модели, её метка (как в чате; без веб-поиска — «без проверки в интернете»),
 * источники поиска. Ещё не загружены — кнопка; разработчику видно, что ушло в модель (только районы).
 */
@Composable
private fun TransitTipsCard(viewModel: TargetViewModel, transit: TransitState?, diagnostics: Boolean, showTag: (ModelTag) -> Boolean) {
    val context = LocalContext.current
    val advice = transit?.advice
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        when {
            transit == null -> GlassButton(stringResource(R.string.transit_tips), onClick = { viewModel.loadTransitTips() }, icon = R.drawable.ic_sym_assistant)
            transit.loading -> Text(stringResource(R.string.transit_loading), style = MaterialTheme.typography.bodyMedium)
            advice != null -> {
                advice.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    advice.noWebSearch -> Hint(stringResource(R.string.transit_no_check, advice.tag.title))
                    showTag(advice.tag) -> Hint(modelLabel(advice.tag))
                }
                if (advice.sources.isNotEmpty()) SourceLinks(advice.sources) { LinkOpener.openSource(context, it) }
            }
            else -> {
                transit.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                GlassButton(stringResource(R.string.transit_retry), onClick = { viewModel.retryTransitTips() })
            }
        }
        if (diagnostics) transit?.sent?.let { Hint(stringResource(R.string.transit_sent, it)) }
    }
}
