package ru.vasev.glasses.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.vasev.glasses.nav.Geo
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Мои места и недавние цели — только на телефоне: `files/places/places.json`, `recent.json` и фото входа
 * `files/places/<id>.jpg` (резервное копирование приложения отключено).
 */
class PlaceStore(context: Context) {

    private val dir = File(context.filesDir, DIR)
    private val placesFile = File(dir, PLACES_FILE)
    private val recentFile = File(dir, RECENT_FILE)
    private val lock = Any()

    private val _places = MutableStateFlow(load(placesFile, Place::fromJson))
    private val _recent = MutableStateFlow(load(recentFile, Destination::fromJson))

    val places: StateFlow<List<Place>> = _places.asStateFlow()

    /** От новых к старым, не больше [RECENT_MAX]. */
    val recent: StateFlow<List<Destination>> = _recent.asStateFlow()

    fun byId(id: String): Place? = _places.value.firstOrNull { it.id == id }

    fun single(kind: PlaceKind): Place? = _places.value.firstOrNull { it.kind == kind }

    /**
     * Сохраняет место. Отель, дом и машина — по одному: новое заменяет прежнее вместе с фото.
     * [replaceId] — изменить это место (своё); [jpeg] — фото входа (null — без фото, или оставить прежнее при [keepPhoto]).
     */
    suspend fun save(place: Place, jpeg: ByteArray?, keepPhoto: Boolean = false, replaceId: String? = null): Place = withContext(Dispatchers.IO) {
        synchronized(lock) {
            dir.mkdirs()
            val old = replaceId?.let(::byId) ?: if (place.kind.single) single(place.kind) else null
            val id = old?.id ?: UUID.randomUUID().toString().take(8)
            val photo = when {
                jpeg != null -> true.also { photoFile(id).writeBytes(jpeg) }
                keepPhoto && old?.hasPhoto == true -> true
                else -> false.also { photoFile(id).delete() }
            }
            val saved = place.copy(id = id, hasPhoto = photo)
            _places.value = listOf(saved) + _places.value.filterNot { it.id == id }
            savePlaces()
            saved
        }
    }

    suspend fun setPhoto(id: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val place = byId(id) ?: return@withContext
            dir.mkdirs()
            photoFile(id).writeBytes(jpeg)
            _places.value = _places.value.map { if (it.id == id) place.copy(hasPhoto = true) else it }
            savePlaces()
        }
    }

    /** Адрес для таксиста, переведённый заранее (пока была сеть). */
    suspend fun setLocal(id: String, name: String?, address: String, language: String, by: String?) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val place = byId(id) ?: return@withContext
            _places.value = _places.value.map {
                if (it.id == id) place.copy(localName = name, localAddress = address, localLanguage = language, localBy = by) else it
            }
            savePlaces()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            photoFile(id).delete()
            _places.value = _places.value.filterNot { it.id == id }
            _recent.value = _recent.value.map { if (it.placeId == id) it.copy(placeId = null) else it }
            savePlaces()
            saveRecent()
        }
    }

    /** «Очистить места и историю»: все места с фото и переводами адресов, недавние цели. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            _places.value = emptyList()
            _recent.value = emptyList()
        }
    }

    /** Цель — в начало «Недавних»; та же точка (ближе [SAME_POINT_M]) не повторяется. */
    suspend fun addRecent(destination: Destination) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            dir.mkdirs()
            val rest = _recent.value.filterNot { Geo.distanceM(it.point, destination.point) < SAME_POINT_M }
            _recent.value = (listOf(destination.copy(usedAt = Instant.now())) + rest).take(RECENT_MAX)
            saveRecent()
        }
    }

    /** Фото входа, уменьшенное до [maxSide] по длинной стороне; null — фото нет. */
    suspend fun photo(place: Place, maxSide: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        val f = photoFile(place.id)
        if (!place.hasPhoto || !f.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun photoFile(id: String) = File(dir, "$id.jpg")

    private fun <T> load(file: File, parse: (JSONObject) -> T): List<T> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { runCatching { parse(array.getJSONObject(it)) }.getOrNull() }
    }.getOrElse {
        Log.w(TAG, "${file.name} read failed: ${it.javaClass.simpleName}")
        emptyList()
    }

    private fun savePlaces() = write(placesFile, JSONArray().apply { _places.value.forEach { put(it.toJson()) } })

    private fun saveRecent() = write(recentFile, JSONArray().apply { _recent.value.forEach { put(it.toJson()) } })

    /** Запись через временный файл: обрыв посреди записи не портит данные. */
    private fun write(file: File, array: JSONArray) {
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val DIR = "places"
        const val PLACES_FILE = "places.json"
        const val RECENT_FILE = "recent.json"
        const val RECENT_MAX = 10
        const val SAME_POINT_M = 30.0
        const val TAG = "Places"
    }
}
