package ru.vasev.glasses.nav

import ru.vasev.glasses.location.DeviceLocation
import ru.vasev.glasses.places.Place
import ru.vasev.glasses.places.PlaceKind
import ru.vasev.glasses.places.PlaceStore
import java.time.Instant
import java.util.Locale

/**
 * Сохранение моих мест: по GPS («Сохранить здесь», «Запомни, это мой отель»), по найденному адресу, из карточки цели.
 * После сохранения адрес для таксиста переводится заранее ([TaxiAddresses.refreshLater]).
 */
class PlaceSaver(
    private val store: PlaceStore,
    private val geocoder: AddressGeocoder,
    private val taxiAddresses: TaxiAddresses,
) {

    /**
     * Место по точке GPS; адрес точки — от геокодера, для подписи и советов по транспорту (город); без сети — без адреса.
     * [replaceId] — заменить это место; [keepPhoto] — снимка нет, оставить прежний.
     */
    suspend fun saveAt(
        kind: PlaceKind,
        name: String,
        fix: DeviceLocation.Fix,
        photo: ByteArray?,
        locale: Locale,
        keepPhoto: Boolean = false,
        replaceId: String? = null,
    ): Place {
        val point = fix.point()
        val address = geocoder.reverse(point, locale)
        return save(
            Place("", kind, name, point, fix.accuracyM, Instant.now(), address = address?.line, country = address?.country, locality = address?.locality),
            photo, keepPhoto, replaceId,
        )
    }

    /** Место по адресу, подтверждённому на экране: координаты — от геокодера, фото входа остаётся. */
    suspend fun saveAddress(edit: PlaceEdit, found: FoundAddress): Place {
        val old = edit.placeId?.let(store::byId)
        return save(
            Place(
                id = "",
                kind = edit.kind,
                name = edit.name.trim().ifEmpty { old?.name.orEmpty() },
                point = found.point,
                accuracyM = null,
                savedAt = Instant.now(),
                address = found.line,
                country = found.country,
                locality = found.locality,
            ),
            jpeg = null,
            keepPhoto = true,
            replaceId = edit.placeId,
        )
    }

    /** Цель — в мои места (своё место с её названием). */
    suspend fun saveTarget(target: Target): Place =
        save(Place("", PlaceKind.CUSTOM, target.label, target.point, null, Instant.now(), address = target.address), jpeg = null)

    private suspend fun save(place: Place, jpeg: ByteArray?, keepPhoto: Boolean = false, replaceId: String? = null): Place {
        val saved = store.save(place, jpeg, keepPhoto, replaceId)
        taxiAddresses.refreshLater(saved.id)
        return saved
    }
}
