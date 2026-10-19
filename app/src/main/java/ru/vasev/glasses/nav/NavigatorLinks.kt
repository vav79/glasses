package ru.vasev.glasses.nav

import java.net.URLEncoder
import java.util.Locale

/** Приложение карт, которому передаётся маршрут. [GEO] — любое приложение карт через `geo:` (выбирает система). */
enum class MapsApp(val packageName: String?) {
    GOOGLE("com.google.android.apps.maps"),
    YANDEX("ru.yandex.yandexmaps"),
    ORGANIC("app.organicmaps"),
    GEO(null),
}

/** Настройка «Навигатор»: «Авто» — в России Яндекс Карты, за границей Google Карты. */
enum class NavigatorChoice { AUTO, YANDEX, GOOGLE }

/** Как добираться — от этого зависит тип маршрута в ссылке. */
enum class TravelMode { WALK, TRANSIT }

/** Готовая ссылка: [packageName] — открыть именно в этом приложении (null — пусть выберет система). */
data class MapsLink(val uri: String, val packageName: String?)

/**
 * Ссылки на маршрут в приложениях карт — по их документации (чистая логика, тесты):
 * - Google: пешком `google.navigation:q=lat,lng&mode=w` (у этой схемы нет транспорта), транспорт —
 *   `https://www.google.com/maps/dir/?api=1&destination=…&travelmode=transit` (без origin — от меня);
 * - Яндекс Карты: `yandexmaps://maps.yandex.ru/?rtext=A~B&rtt=pd|mt` — старт документация задаёт координатами;
 * - Organic Maps: `om://route?sll=…&saddr=…&dll=…&daddr=…&type=pedestrian|transit` — работает без сети;
 * - остальные: `geo:lat,lng?q=lat,lng(подпись)`.
 */
object NavigatorLinks {

    /** Какое приложение открыть: выбранное, если установлено, иначе — что есть. */
    fun pick(choice: NavigatorChoice, country: String?, installed: Set<MapsApp>): MapsApp {
        val preferred = when (choice) {
            NavigatorChoice.YANDEX -> MapsApp.YANDEX
            NavigatorChoice.GOOGLE -> MapsApp.GOOGLE
            NavigatorChoice.AUTO -> if (country.equals("RU", ignoreCase = true)) MapsApp.YANDEX else MapsApp.GOOGLE
        }
        return listOf(preferred, MapsApp.GOOGLE, MapsApp.YANDEX).firstOrNull { it in installed } ?: MapsApp.GEO
    }

    /** Без сети Google пешие маршруты не строит: предупредить и, если есть, предложить Organic Maps. */
    fun needsOfflineWarning(app: MapsApp, online: Boolean): Boolean = !online && app == MapsApp.GOOGLE

    fun route(app: MapsApp, mode: TravelMode, to: GeoPoint, label: String, from: GeoPoint?): MapsLink = when (app) {
        MapsApp.GOOGLE -> when (mode) {
            TravelMode.WALK -> MapsLink("google.navigation:q=${point(to)}&mode=w", app.packageName)
            TravelMode.TRANSIT -> MapsLink(
                "https://www.google.com/maps/dir/?api=1&destination=${encode(point(to))}&travelmode=transit",
                app.packageName,
            )
        }
        MapsApp.YANDEX -> {
            val type = if (mode == TravelMode.WALK) "pd" else "mt"
            MapsLink("yandexmaps://maps.yandex.ru/?rtext=${from?.let(::point).orEmpty()}~${point(to)}&rtt=$type", app.packageName)
        }
        MapsApp.ORGANIC -> {
            val type = if (mode == TravelMode.WALK) "pedestrian" else "transit"
            val start = from?.let { "sll=${point(it)}&saddr=${encode(MY_LOCATION)}&" }.orEmpty()
            MapsLink("om://route?${start}dll=${point(to)}&daddr=${encode(label)}&type=$type", app.packageName)
        }
        MapsApp.GEO -> geo(to, label)
    }

    /** Точка на карте с подписью — «Проверить на карте». */
    fun geo(to: GeoPoint, label: String): MapsLink {
        val p = point(to)
        return MapsLink("geo:$p?q=$p(${encode(label.take(LABEL_MAX))})", null)
    }

    private fun point(p: GeoPoint) = String.format(Locale.US, "%.6f,%.6f", p.latitude, p.longitude)

    /** Пробел — `%20`: так `geo:` и `om:` понимают подпись одинаково. */
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private const val MY_LOCATION = "My location"
    private const val LABEL_MAX = 60
}
