package ru.vasev.glasses.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckTest {

    private fun warnings(url: String) = LinkCheck.analyze(url).warnings

    @Test
    fun `настоящий домен — регистрируемый, а не начало адреса`() {
        val info = LinkCheck.analyze("https://sber.ru.secure-login.xyz/login")
        assertEquals("secure-login.xyz", info.domain)
        assertEquals("sber.ru.secure-login.xyz", info.host)
        assertEquals("ozon.ru", LinkCheck.analyze("https://www.ozon.ru/product/123").domain)
        assertEquals("example.co.uk", LinkCheck.analyze("https://shop.example.co.uk/").domain)
    }

    @Test
    fun `честная ссылка — без предупреждений, можно голосом`() {
        val info = LinkCheck.analyze("https://www.ozon.ru/product/123")
        assertEquals(LinkKind.WEB, info.kind)
        assertTrue(info.warnings.isEmpty())
        assertTrue(info.voiceConfirmAllowed)
        assertTrue(info.openable)
        assertEquals("https://www.ozon.ru/product/123", info.openUri)
        assertTrue(warnings("https://online.sberbank.ru/").isEmpty())
        assertTrue(warnings("https://www.gosuslugi.ru/").isEmpty())
        assertTrue(warnings("https://example.com/menu").isEmpty())
    }

    @Test
    fun `адрес без схемы открывается по https как есть`() {
        val info = LinkCheck.analyze("cafe-roma.ru/menu")
        assertEquals("https://cafe-roma.ru/menu", info.openUri)
        assertTrue(info.warnings.isEmpty())
    }

    @Test
    fun `нет https`() {
        val info = LinkCheck.analyze("http://cafe-roma.ru/")
        assertEquals(setOf(LinkWarning.NO_HTTPS), info.warnings)
        assertFalse(info.voiceConfirmAllowed)
    }

    @Test
    fun `punycode и смесь латиницы с кириллицей`() {
        assertTrue(LinkWarning.LOOKALIKE_CHARS in warnings("https://xn--80aaxitdbjk.xn--p1ai/"))
        // «sbеr» с кириллической «е».
        val mixed = LinkCheck.analyze("https://sbеr.ru/")
        assertTrue(LinkWarning.LOOKALIKE_CHARS in mixed.warnings)
        assertTrue(LinkWarning.SIMILAR_TO_KNOWN in mixed.warnings)
    }

    @Test
    fun `сокращатели ссылок`() {
        assertTrue(LinkWarning.SHORTENER in warnings("https://bit.ly/3abcDEF"))
        assertTrue(LinkWarning.SHORTENER in warnings("https://clck.ru/36xYz"))
        assertTrue(LinkWarning.SHORTENER in warnings("https://vk.cc/abc"))
    }

    @Test
    fun `ip вместо домена`() {
        val info = LinkCheck.analyze("https://192.168.10.5/pay")
        assertTrue(LinkWarning.IP_ADDRESS in info.warnings)
        assertEquals("192.168.10.5", info.domain)
        assertTrue(LinkWarning.IP_ADDRESS in warnings("http://[2001:db8::1]/"))
    }

    @Test
    fun `собака перед доменом — настоящий сайт после неё`() {
        val info = LinkCheck.analyze("https://www.sberbank.ru@evil-site.com/login")
        assertTrue(LinkWarning.AT_SIGN in info.warnings)
        assertEquals("evil-site.com", info.domain)
        // «@» в пути (профили) — не подвох.
        assertFalse(LinkWarning.AT_SIGN in warnings("https://medium.com/@author/post"))
    }

    @Test
    fun `похожие на известные домены`() {
        val similar = mapOf(
            "https://sber.ru.secure-login.xyz/" to "sberbank.ru",
            "https://sberbank-online.top/" to "sberbank.ru",
            "https://gosuslugl.ru/" to "gosuslugi.ru",
            "https://gosuslugi.com/" to "gosuslugi.ru",
            "https://0z0n.ru/" to "ozon.ru",
            "https://wildberies.ru/" to "wildberries.ru",
            "https://paypal-secure.com/" to "paypal.com",
            "https://ozon-sale.shop/" to "ozon.ru",
        )
        for ((url, known) in similar) {
            val info = LinkCheck.analyze(url)
            assertTrue(url, LinkWarning.SIMILAR_TO_KNOWN in info.warnings)
            assertEquals(url, known, info.similarTo)
        }
    }

    @Test
    fun `обычные сайты не считаются подделками`() {
        for (url in listOf("https://mall.ru/", "https://oven.com/", "https://ozone.com/", "https://pineapple.com/", "https://analog.ru/", "https://yandex.net/", "https://vk.com/")) {
            assertFalse(url, LinkWarning.SIMILAR_TO_KNOWN in warnings(url))
        }
    }

    @Test
    fun `скачивание apk`() {
        val info = LinkCheck.analyze("https://example.com/files/Bank-Update.APK")
        assertTrue(LinkWarning.APK in info.warnings)
        assertFalse(info.voiceConfirmAllowed)
    }

    @Test
    fun `оплата по СБП — сумма в копейках, получателя в ссылке нет`() {
        val info = LinkCheck.analyze("https://qr.nspk.ru/AS1000670LSS7DN18SJQDNP4B05KLJL2?type=01&bank=100000000111&sum=15050&cur=RUB&crc=AB75")
        assertEquals(LinkKind.PAYMENT_SBP, info.kind)
        assertEquals(15050L, info.payment?.amountKopecks)
        assertNull(info.payment?.recipient)
        assertFalse(info.voiceConfirmAllowed)
        assertTrue(info.openable)
    }

    @Test
    fun `платёж по ГОСТ — реквизиты, открыть нечего`() {
        val info = LinkCheck.analyze("ST00012|Name=ООО «Ромашка»|PersonalAcc=40702810900000000001|BankName=ПАО Сбербанк|BIC=044525225|CorrespAcc=30101810400000000225|Sum=150000|Purpose=Оплата заказа 12")
        assertEquals(LinkKind.PAYMENT_GOST, info.kind)
        assertEquals(150000L, info.payment?.amountKopecks)
        assertEquals("ООО «Ромашка»", info.payment?.recipient)
        assertEquals("ПАО Сбербанк", info.payment?.bank)
        assertEquals("Оплата заказа 12", info.payment?.purpose)
        assertFalse(info.openable)
    }

    @Test
    fun `wifi с экранированием`() {
        val info = LinkCheck.analyze("WIFI:T:WPA;S:Cafe\\;Roma;P:pa\\:ss;H:false;;")
        assertEquals(LinkKind.WIFI, info.kind)
        assertEquals("Cafe;Roma", info.wifi?.ssid)
        assertEquals("pa:ss", info.wifi?.password)
        assertEquals("WPA", info.wifi?.security)
        assertTrue(info.wifi!!.hasPassword)
        assertFalse(info.voiceConfirmAllowed)
        assertFalse(LinkCheck.analyze("WIFI:T:nopass;S:Free;;").wifi!!.hasPassword)
    }

    @Test
    fun `телефон, смс, почта и чужие схемы — только кнопкой`() {
        assertEquals(LinkKind.PHONE, LinkCheck.analyze("tel:+79991234567").kind)
        assertEquals(LinkKind.SMS, LinkCheck.analyze("sms:+79991234567?body=hi").kind)
        assertEquals(LinkKind.SMS, LinkCheck.analyze("SMSTO:900:text").kind)
        assertEquals(LinkKind.EMAIL, LinkCheck.analyze("mailto:info@cafe.ru").kind)
        val tg = LinkCheck.analyze("tg://resolve?domain=cafe")
        assertEquals(LinkKind.OTHER_SCHEME, tg.kind)
        assertFalse(tg.voiceConfirmAllowed)
        assertTrue(tg.openable)
    }

    @Test
    fun `опасные схемы не открываются вовсе`() {
        for (raw in listOf(
            "javascript:alert(1)", "JavaScript:void(0)", "file:///sdcard/a.html", "content://com.app/secret",
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end", "data:text/html;base64,PHNjcmlwdD4=",
        )) {
            val info = LinkCheck.analyze(raw)
            assertEquals(raw, LinkKind.BLOCKED, info.kind)
            assertFalse(raw, info.openable)
        }
    }

    @Test
    fun `адреса в распознанном тексте`() {
        val text = """
            Меню и доставка: www.cafe-roma.ru/menu
            Отзывы: https://example.com/reviews?id=5.
            Пишите: info@cafe-roma.ru
            Акция на sber.ru.secure-login.xyz, звоните 8-800-555-35-35
        """.trimIndent()
        assertEquals(
            listOf("www.cafe-roma.ru/menu", "https://example.com/reviews?id=5", "sber.ru.secure-login.xyz"),
            LinkCheck.findInText(text),
        )
    }

    @Test
    fun `расстояние Левенштейна`() {
        assertEquals(1, LinkCheck.distance("gosuslugl", "gosuslugi"))
        assertEquals(1, LinkCheck.distance("wildberies", "wildberries"))
        assertEquals(3, LinkCheck.distance("abc", ""))
        assertEquals(0, LinkCheck.distance("ozon", "ozon"))
    }
}
