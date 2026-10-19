package ru.vasev.glasses.places

import org.json.JSONObject
import ru.vasev.glasses.nav.GeoPoint
import java.time.Instant

/** Вид моего места: отель, дом и машина — по одному (повторное сохранение заменяет), своих — сколько угодно. */
enum class PlaceKind {
    HOTEL, HOME, CAR, CUSTOM;

    /** Вид, у которого место может быть только одно. */
    val single: Boolean get() = this != CUSTOM
}

/**
 * Моё место: координаты, когда сохранено и, если есть, фото входа (лежит рядом файлом), адрес текстом
 * и где это (страна и город — для советов по транспорту без точных координат).
 * [accuracyM] — точность GPS, если место сохранено «здесь»; null — координаты нашёл геокодер по адресу.
 */
data class Place(
    val id: String,
    val kind: PlaceKind,
    /** Своё название; у отеля, дома и машины может быть пустым (тогда подпись — вид места). */
    val name: String,
    val point: GeoPoint,
    val accuracyM: Float?,
    val savedAt: Instant,
    val hasPhoto: Boolean = false,
    val address: String? = null,
    /** ISO 3166-1 alpha-2. */
    val country: String? = null,
    val locality: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("name", name)
        put("lat", point.latitude)
        put("lon", point.longitude)
        accuracyM?.let { put("accuracy_m", it.toDouble()) }
        put("saved_at", savedAt.toString())
        put("photo", hasPhoto)
        address?.let { put("address", it) }
        country?.let { put("country", it) }
        locality?.let { put("locality", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Place(
            id = o.getString("id"),
            kind = PlaceKind.valueOf(o.getString("kind")),
            name = o.optString("name"),
            point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
            accuracyM = if (o.has("accuracy_m")) o.getDouble("accuracy_m").toFloat() else null,
            savedAt = Instant.parse(o.getString("saved_at")),
            hasPhoto = o.optBoolean("photo"),
            address = o.optString("address").ifEmpty { null },
            country = o.optString("country").ifEmpty { null },
            locality = o.optString("locality").ifEmpty { null },
        )
    }
}

/** Цель, куда я недавно шёл (список «Недавние»); [placeId] — если это одно из моих мест. */
data class Destination(
    val label: String,
    val point: GeoPoint,
    val address: String? = null,
    val placeId: String? = null,
    val usedAt: Instant = Instant.now(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("lat", point.latitude)
        put("lon", point.longitude)
        address?.let { put("address", it) }
        placeId?.let { put("place_id", it) }
        put("used_at", usedAt.toString())
    }

    companion object {
        fun fromJson(o: JSONObject) = Destination(
            label = o.getString("label"),
            point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
            address = o.optString("address").ifEmpty { null },
            placeId = o.optString("place_id").ifEmpty { null },
            usedAt = Instant.parse(o.getString("used_at")),
        )
    }
}
