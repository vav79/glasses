package ru.vasev.glasses.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorLinksTest {

    private val to = GeoPoint(13.743700, 100.488800)
    private val me = GeoPoint(13.730000, 100.560000)
    private val all = setOf(MapsApp.GOOGLE, MapsApp.YANDEX, MapsApp.ORGANIC)

    @Test
    fun `авто — в России Яндекс, за границей Google`() {
        assertEquals(MapsApp.YANDEX, NavigatorLinks.pick(NavigatorChoice.AUTO, "RU", all))
        assertEquals(MapsApp.GOOGLE, NavigatorLinks.pick(NavigatorChoice.AUTO, "TH", all))
        assertEquals(MapsApp.GOOGLE, NavigatorLinks.pick(NavigatorChoice.AUTO, null, all))
    }

    @Test
    fun `выбранного нет — то, что установлено, иначе geo`() {
        assertEquals(MapsApp.GOOGLE, NavigatorLinks.pick(NavigatorChoice.YANDEX, "RU", setOf(MapsApp.GOOGLE)))
        assertEquals(MapsApp.YANDEX, NavigatorLinks.pick(NavigatorChoice.GOOGLE, "TH", setOf(MapsApp.YANDEX)))
        assertEquals(MapsApp.GEO, NavigatorLinks.pick(NavigatorChoice.AUTO, "TH", setOf(MapsApp.ORGANIC)))
    }

    @Test
    fun `Google — пешком через google navigation, транспорт через Maps URL`() {
        assertEquals(
            MapsLink("google.navigation:q=13.743700,100.488800&mode=w", "com.google.android.apps.maps"),
            NavigatorLinks.route(MapsApp.GOOGLE, TravelMode.WALK, to, "Wat Arun", me),
        )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=13.743700%2C100.488800&travelmode=transit",
            NavigatorLinks.route(MapsApp.GOOGLE, TravelMode.TRANSIT, to, "Wat Arun", me).uri,
        )
    }

    @Test
    fun `Яндекс — rtext от меня до цели, pd пешком и mt транспортом`() {
        assertEquals(
            "yandexmaps://maps.yandex.ru/?rtext=13.730000,100.560000~13.743700,100.488800&rtt=pd",
            NavigatorLinks.route(MapsApp.YANDEX, TravelMode.WALK, to, "x", me).uri,
        )
        assertEquals(
            "yandexmaps://maps.yandex.ru/?rtext=~13.743700,100.488800&rtt=mt",
            NavigatorLinks.route(MapsApp.YANDEX, TravelMode.TRANSIT, to, "x", from = null).uri,
        )
    }

    @Test
    fun `Organic Maps и geo с подписью`() {
        assertEquals(
            "om://route?sll=13.730000,100.560000&saddr=My%20location&dll=13.743700,100.488800&daddr=Wat%20Arun&type=pedestrian",
            NavigatorLinks.route(MapsApp.ORGANIC, TravelMode.WALK, to, "Wat Arun", me).uri,
        )
        assertEquals(
            MapsLink("geo:13.743700,100.488800?q=13.743700,100.488800(%D0%9E%D1%82%D0%B5%D0%BB%D1%8C)", null),
            NavigatorLinks.geo(to, "Отель"),
        )
    }

    @Test
    fun `без сети предупреждаем только про Google`() {
        assertTrue(NavigatorLinks.needsOfflineWarning(MapsApp.GOOGLE, online = false))
        assertFalse(NavigatorLinks.needsOfflineWarning(MapsApp.GOOGLE, online = true))
        assertFalse(NavigatorLinks.needsOfflineWarning(MapsApp.ORGANIC, online = false))
    }
}
