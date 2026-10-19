package ru.vasev.glasses.nav

import java.net.URLEncoder
import java.util.Locale

/**
 * Приложения такси. С пунктом назначения открываются только те, у кого ссылка документирована:
 * Яндекс Go (`https://3.redirect.appmetrica.yandex.com/route?end-lat=…&end-lon=…`) и Uber
 * (`uber://?action=setPickup&pickup=my_location&dropoff[latitude]=…`); остальные — просто запускаем,
 * адрес водителю показывает экран «Адрес для таксиста».
 */
enum class TaxiApp(val title: String, val packageName: String) {
    GRAB("Grab", "com.grabtaxi.passenger"),
    BOLT("Bolt", "ee.mtakso.client"),
    // Подпись на экране — из ресурсов (R.string.taxi_yandex_go): по-русски «Яндекс Go».
    YANDEX_GO("Yandex Go", "ru.yandex.taxi"),
    UBER("Uber", "com.ubercab"),
    INDRIVE("inDrive", "sinet.startup.inDriver"),
    GOJEK("Gojek", "com.gojek.app"),
    CAREEM("Careem", "com.careem.acma"),
    DIDI("DiDi", "com.didiglobal.passenger"),
    MAXIM("Maxim", "com.taxsee.taxsee"),
    FREENOW("FREENOW", "taxi.android.client"),
    CABIFY("Cabify", "com.cabify.rider"),
    LYFT("Lyft", "me.lyft.android"),
}

object TaxiLinks {

    /** Ссылка с пунктом назначения; null — у приложения такой нет, открываем его как есть. */
    fun destination(app: TaxiApp, to: GeoPoint, label: String): String? {
        val lat = String.format(Locale.US, "%.6f", to.latitude)
        val lon = String.format(Locale.US, "%.6f", to.longitude)
        return when (app) {
            TaxiApp.YANDEX_GO -> "https://3.redirect.appmetrica.yandex.com/route?end-lat=$lat&end-lon=$lon&ref=$REF&appmetrica_tracking_id=$YANDEX_GO_TRACKING"
            TaxiApp.UBER -> "uber://?action=setPickup&pickup=my_location&dropoff%5Blatitude%5D=$lat&dropoff%5Blongitude%5D=$lon" +
                "&dropoff%5Bnickname%5D=${URLEncoder.encode(label.take(LABEL_MAX), "UTF-8").replace("+", "%20")}"
            else -> null
        }
    }

    private const val REF = "ru.vasev.glasses"

    /** Документированный id «страница приложения в магазине» — если Яндекс Go вдруг не установлен. */
    private const val YANDEX_GO_TRACKING = "1178268795219780156"
    private const val LABEL_MAX = 60
}
