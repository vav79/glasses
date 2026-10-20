package ru.vasev.glasses.nav

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.L
import ru.vasev.glasses.places.Destination
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind

/**
 * Мои места и недавние цели: плитки на главной «Навигации», экран моего места («Сохранить здесь» — GPS, фото входа
 * и время; адрес — через поиск на том же экране), «Очистить места и историю». Открытое место — в [NavSession].
 */
class PlacesViewModel(app: Application) : AndroidViewModel(app) {

    private val g = app as GlassesApp
    private val session = g.navSession
    private val store = g.places

    val places: StateFlow<List<Place>> = store.places
    val recent: StateFlow<List<Destination>> = store.recent

    fun openPlaceTarget(place: Place) = session.openPlaceTarget(place)

    fun openRecent(destination: Destination) =
        session.openTarget(Target(destination.label, destination.point, destination.address, destination.placeId?.takeIf { store.byId(it) != null }))

    fun openPlace(place: Place) = session.openPlace(place)

    fun newPlace(kind: PlaceKind) = session.newPlace(kind)

    fun setPlaceName(name: String) {
        val edit = session.place.value ?: return
        session.updatePlace(edit.copy(name = name))
    }

    fun closePlace() = session.closePlace()

    /** «Сохранить здесь»: координаты GPS, фото входа (из выбранного источника) и время; прежнее место того же вида заменяется. */
    fun savePlaceHere() = session.launchTask(viewModelScope, NavBusy.LOCATING) {
        val edit = session.place.value ?: return@launchTask
        if (!session.ensureLocation()) {
            session.notify(L.s(R.string.nav_location_needed))
            return@launchTask
        }
        val fix = session.fixForPlace(useFake = false) ?: run {
            session.notify(L.s(R.string.nav_no_fix))
            return@launchTask
        }
        session.setBusy(NavBusy.CAPTURING)
        val photo = when (val shot = session.photos.shoot(R.string.nav_viewfinder_entrance, quiet = true, allowSkip = true)) {
            is Shot.Taken -> shot.jpeg
            is Shot.Failed -> null.also { session.notify(shot.notice) }
            Shot.None -> null
            Shot.Cancelled -> return@launchTask
        }
        val saved = session.saver.saveAt(
            kind = edit.kind,
            name = edit.name.trim(),
            fix = fix,
            photo = photo,
            locale = session.appLocale(),
            keepPhoto = photo == null && edit.placeId != null,
            replaceId = edit.placeId,
        )
        Log.d(NavSession.TAG, "place saved kind=${saved.kind} photo=${saved.hasPhoto} accuracy=${fix.accuracyM}")
        session.updatePlace(edit.copy(placeId = saved.id))
    }

    /** Новое фото входа для сохранённого места. */
    fun retakePlacePhoto() = session.launchTask(viewModelScope, NavBusy.CAPTURING) {
        val id = session.place.value?.placeId ?: return@launchTask
        when (val shot = session.photos.shoot(R.string.nav_viewfinder_entrance)) {
            is Shot.Taken -> store.setPhoto(id, shot.jpeg)
            is Shot.Failed -> session.notify(shot.notice)
            Shot.None, Shot.Cancelled -> Unit
        }
    }

    fun deletePlace() {
        val id = session.place.value?.placeId ?: return
        viewModelScope.launch {
            store.delete(id)
            session.closePlace()
        }
    }

    /** «Адрес для таксиста» у моего места: карточка цели под ним, чтобы «Назад» из такси вёл к ней. */
    fun taxiForPlace(place: Place) {
        session.closePlace()
        session.openPlaceTarget(place)
        session.openTaxi()
    }

    fun goToPlace(place: Place) {
        session.closePlace()
        session.openPlaceTarget(place)
    }

    /** «Очистить места и историю»: места с фото и переводами, недавние, начало маршрута, советы, открытая цель, компас. */
    fun clearAll() = session.clearAll()

    suspend fun placePhoto(place: Place, maxSide: Int = 1024) = store.photo(place, maxSide)
}
