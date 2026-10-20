package ru.vasev.glasses.nav

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Адрес, найденный геокодером: строка адреса, координаты и где это (страна и город). */
data class FoundAddress(
    val line: String,
    val point: GeoPoint,
    val country: String?,
    val locality: String?,
    /** Район (sub-locality или округ) — для советов по транспорту без точного адреса. */
    val district: String? = null,
    /** Название места («Эрмитаж»), если геокодер его знает; номер дома или улица — не название. */
    val name: String? = null,
)

/** Итог геокодера: пустой ответ — «не нашёл»; сбой, нет сервиса или сети — «недоступен» (это разные подсказки). */
sealed interface GeocodeResult {
    data class Found(val addresses: List<FoundAddress>) : GeocodeResult
    data object NotFound : GeocodeResult
    data object Unavailable : GeocodeResult
}

/**
 * Встроенный геокодер Android. Ограничения (документация `Geocoder`): работает через сервис Google и только
 * с сетью; пустой ответ не отличает «не нашёл» от «нет сервиса»; результат — лучшая догадка без гарантий
 * точности (поэтому найденную точку всегда предлагаем проверить на карте); на нужном языке данных может не быть.
 * Вызывается только по «Готово», не на каждую букву: квоты не документированы.
 */
class AddressGeocoder(private val context: Context, private val online: () -> Boolean) {

    /** Адреса по тексту; рядом со мной ([near], ±[BOX_DEG]) — первыми, ничего рядом — по всему миру. */
    suspend fun search(query: String, near: GeoPoint?, locale: Locale): GeocodeResult {
        if (!Geocoder.isPresent() || !online()) return GeocodeResult.Unavailable
        val geocoder = Geocoder(context, locale)
        val nearby = near?.let { p ->
            val (s, w, n, e) = listOf(p.latitude - BOX_DEG, p.longitude - BOX_DEG, p.latitude + BOX_DEG, p.longitude + BOX_DEG)
            call(
                legacy = { @Suppress("DEPRECATION") geocoder.getFromLocationName(query, MAX_RESULTS, s, w, n, e) },
                modern = { geocoder.getFromLocationName(query, MAX_RESULTS, s, w, n, e, it) },
            ) ?: return GeocodeResult.Unavailable
        }
        val list = nearby?.takeIf { it.isNotEmpty() } ?: call(
            legacy = { @Suppress("DEPRECATION") geocoder.getFromLocationName(query, MAX_RESULTS) },
            modern = { geocoder.getFromLocationName(query, MAX_RESULTS, it) },
        ) ?: return GeocodeResult.Unavailable
        val found = list.mapNotNull(::toFound)
        Log.d(TAG, "search results=${found.size} nearby=${nearby?.size}")
        return if (found.isEmpty()) GeocodeResult.NotFound else GeocodeResult.Found(found)
    }

    /** Адрес точки на языке [locale]; null — не нашёл или геокодер недоступен. */
    suspend fun reverse(point: GeoPoint, locale: Locale): FoundAddress? {
        if (!Geocoder.isPresent() || !online()) return null
        val geocoder = Geocoder(context, locale)
        val list = call(
            legacy = { @Suppress("DEPRECATION") geocoder.getFromLocation(point.latitude, point.longitude, 1) },
            modern = { geocoder.getFromLocation(point.latitude, point.longitude, 1, it) },
        )
        return list?.firstOrNull()?.let(::toFound)?.copy(point = point)
    }

    /**
     * Один запрос: на Android 13+ — [modern] со слушателем (блокирующие вызовы там устарели), раньше — [legacy]
     * в IO. null — сбой или таймаут.
     */
    private suspend fun call(legacy: () -> List<Address>?, modern: (Geocoder.GeocodeListener) -> Unit): List<Address>? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>?> { cont ->
                    modern(
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.toList())
                            }

                            override fun onError(errorMessage: String?) {
                                Log.w(TAG, "geocoder error: $errorMessage")
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) { legacy().orEmpty() }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "geocoder failed: ${e.javaClass.simpleName}")
        null
    }

    private fun toFound(a: Address): FoundAddress? {
        if (!a.hasLatitude() || !a.hasLongitude()) return null
        val line = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").ifBlank {
            listOfNotNull(a.featureName, a.thoroughfare, a.locality, a.countryName).distinct().joinToString(", ")
        }
        return FoundAddress(
            line, GeoPoint(a.latitude, a.longitude), a.countryCode?.uppercase(), a.locality ?: a.subAdminArea ?: a.adminArea,
            district = a.subLocality ?: a.subAdminArea?.takeIf { it != a.locality },
            name = a.featureName?.takeIf { f -> f.any(Char::isLetter) && f != a.thoroughfare && f != a.locality && f != a.countryName && f != a.adminArea },
        )
    }

    private companion object {
        const val TAG = "Geocoder"
        const val MAX_RESULTS = 5
        const val BOX_DEG = 0.5
        const val TIMEOUT_MS = 10_000L
    }
}
