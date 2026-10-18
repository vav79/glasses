package ru.vasev.glasses.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Где телефон: координаты для заметок и страна для валюты на ценниках.
 * Координаты — только с разрешением на геопозицию (его запрашивает экран при первой заметке).
 */
class DeviceLocation(private val context: Context) {

    data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Float?)

    private val manager = context.getSystemService(LocationManager::class.java)

    val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Текущие координаты (ждём не дольше [timeoutMs]), иначе последние известные; null — нет разрешения или фиксации. */
    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = FIX_TIMEOUT_MS): Fix? {
        if (!granted || manager == null) return null
        val provider = provider() ?: return lastKnown()
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        }
        return fresh?.toFix() ?: lastKnown()
    }

    /**
     * Страна (ISO 3166-1 alpha-2): сначала мобильная сеть — без разрешений и без интернета; нет SIM или сети —
     * координаты + Geocoder (если разрешение на геопозицию уже есть). null — не удалось.
     */
    suspend fun country(): String? {
        networkCountry()?.let { return it }
        if (!granted || !Geocoder.isPresent()) return null
        val fix = current(COUNTRY_FIX_TIMEOUT_MS) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.US).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()?.countryCode
            }.onFailure { Log.w(TAG, "geocoder failed: ${it.javaClass.simpleName}") }.getOrNull()?.uppercase()
        }
    }

    /** Страна мобильной сети, к которой телефон подключён сейчас (в роуминге — страна пребывания). */
    fun networkCountry(): String? =
        context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.trim()?.uppercase()?.takeIf { it.length == 2 }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Fix? {
        val m = manager ?: return null
        return m.getProviders(true)
            .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    private fun provider(): String? {
        val m = manager ?: return null
        val enabled = m.getProviders(true)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else null)

    private companion object {
        const val TAG = "Location"
        const val FIX_TIMEOUT_MS = 8_000L
        const val COUNTRY_FIX_TIMEOUT_MS = 4_000L
    }
}
