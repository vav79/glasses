package ru.vasev.glasses.nav

import android.util.Log
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.i18n.LanguageSettings
import ru.vasev.glasses.places.PlaceKind
import ru.vasev.glasses.speech.AudioChannel

/**
 * Голосовые команды навигации — с микрофонов «Ассистента» и «Камеры», до модели: выполнить и ответить голосом
 * (с очками на экран не смотрят). Состояние экранов меняется через [NavSession].
 */
class NavVoiceCommands(private val g: GlassesApp, private val session: NavSession) {

    private val store get() = g.places

    fun vocabulary() = NavVocabulary(
        goPrefixes = L.array(R.array.nav_cmd_go_prefixes),
        returnPhrases = L.array(R.array.nav_cmd_return),
        backToStartPhrases = L.array(R.array.nav_cmd_back_to_start),
        taxiPhrases = L.array(R.array.nav_cmd_taxi),
        howFarPhrases = L.array(R.array.nav_cmd_how_far),
        whereCarPhrases = L.array(R.array.nav_cmd_where_car),
        rememberTriggers = L.array(R.array.remember_triggers),
        rememberPlace = mapOf(
            PlaceKind.HOTEL to L.array(R.array.nav_remember_hotel),
            PlaceKind.HOME to L.array(R.array.nav_remember_home),
            PlaceKind.CAR to L.array(R.array.nav_remember_car),
        ),
    )

    /** true — показать вкладку «Навигация» (цель, такси); «сколько осталось» и «запомни место» — без переключения. */
    fun showsTab(command: NavCommand): Boolean = command !is NavCommand.HowFar && command !is NavCommand.RememberPlace

    suspend fun handle(command: NavCommand) {
        Log.d(TAG, "voice command ${command.javaClass.simpleName}")
        when (command) {
            is NavCommand.GoTo -> goTo(command.query)
            is NavCommand.GoToPlace -> goToPlace(command.kind)
            NavCommand.Return -> {
                val place = store.single(PlaceKind.HOTEL) ?: store.single(PlaceKind.HOME)
                if (place == null) say(L.s(R.string.nav_say_return_none)) else target(Target(placeLabel(place), place.point, place.address, place.id))
            }
            NavCommand.BackToStart -> {
                val start = session.routeStart
                if (start == null) say(L.s(R.string.nav_say_start_none)) else target(Target(L.s(R.string.nav_route_start), start, null), keepStart = true)
            }
            NavCommand.ShowTaxi -> {
                val hotel = store.single(PlaceKind.HOTEL)
                val target = session.target.value ?: g.compass.state.value?.target
                    ?: hotel?.let { Target(placeLabel(it), it.point, it.address, it.id) }
                if (target == null) {
                    say(L.s(R.string.nav_say_no_target))
                } else {
                    session.showTarget(target)
                    session.openTaxi()
                    say(L.s(R.string.nav_say_taxi))
                }
            }
            NavCommand.HowFar -> {
                val target = session.target.value
                when {
                    g.compass.state.value?.running == true -> g.compass.sayStatus()
                    target != null -> distance(target)
                    else -> say(L.s(R.string.nav_say_no_target))
                }
            }
            is NavCommand.RememberPlace -> remember(command.kind)
        }
    }

    private suspend fun goTo(query: String) {
        val aliases = session.placeAliases()
        NavQuery.matchPlace(query, store.places.value, aliases)?.let { return target(Target(placeLabel(it), it.point, it.address, it.id)) }
        NavQuery.kindOf(query, aliases)?.let { return goToPlace(it) }
        session.closePlace()
        session.prefillSearch(SearchPrefill(query, offerAiName = false))
        val here = session.freshHere()
        when (val result = session.geocoder.search(query, here, session.appLocale())) {
            is GeocodeResult.Found -> {
                // Голосом список не выбрать: берём ближайший из найденных («Эрмитаж» — не во Франции), название — от геокодера.
                val found = here?.let { h -> result.addresses.minBy { Geo.distanceM(h, it.point) } } ?: result.addresses.first()
                target(Target(found.name ?: query, found.point, found.line))
            }
            GeocodeResult.NotFound -> {
                session.prefillSearch(SearchPrefill(query, offerAiName = true))
                session.notify(L.s(R.string.nav_not_found))
                say(L.s(R.string.nav_say_not_found, query))
            }
            GeocodeResult.Unavailable -> say(session.geocoderUnavailableText())
        }
    }

    private suspend fun goToPlace(kind: PlaceKind) {
        val place = store.single(kind)
        if (place == null) say(L.s(R.string.nav_say_not_saved, L.s(kindTitle(kind)))) else target(Target(placeLabel(place), place.point, place.address, place.id))
    }

    /** Цель выбрана голосом: близко — сразу «Компас» (он говорит сам), далеко — расстояние и варианты на экране. */
    private suspend fun target(target: Target, keepStart: Boolean = false) {
        val here = session.freshHere()
        if (!keepStart) session.routeStart = here
        session.openTarget(target)
        if (here != null && Geo.compassSuitable(Geo.distanceM(here, target.point))) {
            session.startCompass(target)
        } else {
            distance(target)
        }
    }

    private suspend fun distance(target: Target) {
        val here = session.freshHere() ?: return say(L.s(R.string.nav_say_target_no_fix, target.label))
        val d = Geo.distanceM(here, target.point)
        val text = Geo.distanceText(d, session.appLocale())
        val distance = if (text.km) L.s(R.string.nav_km, text.value) else L.q(R.plurals.compass_meters, CompassMath.spokenMeters(d))
        val minutes = Geo.walkMinutes(d)
        val walk = if (minutes <= SPOKEN_WALK_MAX_MIN) L.s(R.string.nav_say_walk, L.q(R.plurals.compass_about_minutes, minutes)) else "."
        val options = if (Geo.transitFirst(d)) L.s(R.string.nav_say_options) else ""
        // Очень далеко — скорее всего, геокодер нашёл не то место (голосом список не выбрать).
        val check = if (d > FAR_CHECK_M && target.placeId == null) L.s(R.string.nav_say_far_check) else ""
        say(listOf(L.s(R.string.nav_say_target, target.label, distance) + walk, options, check).filter(String::isNotBlank).joinToString(" "))
    }

    /** «Запомни, это мой отель»: место здесь, время и фото входа — как «Сохранить здесь», без экрана. */
    private suspend fun remember(kind: PlaceKind) {
        if (!g.location.granted && session.hooks?.requestLocation?.invoke() != true) return say(L.s(R.string.nav_location_needed))
        val fix = session.fixForPlace(useFake = true) ?: return say(L.s(R.string.nav_no_fix))
        // Голосом — телефон в кармане: снимок сразу, без видоискателя.
        val photo = (session.photos.shoot(hint = null, quiet = true) as? Shot.Taken)?.jpeg
        val saved = session.saver.saveAt(kind, store.single(kind)?.name.orEmpty(), fix, photo, session.appLocale())
        Log.d(TAG, "voice remember kind=$kind photo=${saved.hasPhoto}")
        val title = L.s(kindTitle(kind))
        say(L.s(if (saved.hasPhoto) R.string.nav_say_remembered else R.string.nav_say_remembered_no_photo, title))
    }

    private suspend fun say(text: String) {
        Log.d(TAG, "say: $text")
        runCatching { g.audioOutput.speak(text, LanguageSettings.current.value.locale, AudioChannel.ME) }
    }

    private companion object {
        const val TAG = NavSession.TAG
        /** Дольше — время пешком вслух не говорим (это не пеший путь). */
        const val SPOKEN_WALK_MAX_MIN = 60
        const val FAR_CHECK_M = 300_000.0
    }
}
