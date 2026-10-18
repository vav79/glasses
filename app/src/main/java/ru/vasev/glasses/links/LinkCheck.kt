package ru.vasev.glasses.links

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Что за ссылка: от этого зависит, как её открывать и можно ли подтвердить голосом. */
enum class LinkKind {
    WEB,

    /** Оплата по СБП: ссылка `qr.nspk.ru` (откроет приложение банка). */
    PAYMENT_SBP,

    /** Платёжные реквизиты по ГОСТ Р 56042 (`ST00012|Name=…|Sum=…`) — не адрес, открыть нечего, только показать. */
    PAYMENT_GOST,
    WIFI,
    PHONE,
    SMS,
    EMAIL,

    /** Прочие схемы (`tg:`, `market:` …) — только кнопкой. */
    OTHER_SCHEME,

    /** `javascript:`, `file:`, `content:`, `intent:`, `data:` — не открываются вовсе. */
    BLOCKED,

    /** Не похоже на адрес. */
    UNKNOWN,
}

/** Предупреждения — вслух и на карточке; с любым из них ссылка открывается только кнопкой. */
enum class LinkWarning {
    NO_HTTPS,

    /** Домен из похожих символов: punycode `xn--` (в том числе смесь латиницы и кириллицы). */
    LOOKALIKE_CHARS,

    /** Сокращатель ссылок: настоящий адрес не виден. */
    SHORTENER,
    IP_ADDRESS,

    /** «@» перед доменом: `https://sberbank.ru@evil.com` ведёт на evil.com. */
    AT_SIGN,

    /** Похож на известный домен (банки, Госуслуги, маркетплейсы, почта), но не он. */
    SIMILAR_TO_KNOWN,

    /** Скачивание приложения в обход магазина. */
    APK,
}

/** Сумма в копейках (у СБП и ГОСТ сумма в копейках) и получатель, если указан. */
data class Payment(val amountKopecks: Long?, val recipient: String?, val bank: String? = null, val purpose: String? = null)

data class Wifi(val ssid: String, val security: String?, val hasPassword: Boolean, val password: String?, val hidden: Boolean)

/**
 * Разобранная ссылка. [domain] — настоящий домен («регистрируемый»: `sber.ru.secure-login.xyz` → `secure-login.xyz`)
 * или IP; [host] — полный хост; [openUri] — что открывать (ровно исходный адрес; без схемы — с `https://`).
 */
data class LinkInfo(
    val raw: String,
    val kind: LinkKind,
    val openUri: String? = null,
    val host: String? = null,
    val domain: String? = null,
    val warnings: Set<LinkWarning> = emptySet(),
    /** Какой известный домен напоминает (для [LinkWarning.SIMILAR_TO_KNOWN]). */
    val similarTo: String? = null,
    val payment: Payment? = null,
    val wifi: Wifi? = null,
) {
    /** Можно ли подтвердить голосом: обычный сайт без единого предупреждения. */
    val voiceConfirmAllowed: Boolean get() = kind == LinkKind.WEB && warnings.isEmpty()

    /** Есть ли что открывать (реквизиты ГОСТ и запрещённые схемы — нет). */
    val openable: Boolean get() = kind != LinkKind.BLOCKED && kind != LinkKind.PAYMENT_GOST && kind != LinkKind.UNKNOWN && openUri != null
}

/**
 * Разбор адресов из QR-кодов и распознанного текста и проверка на подвохи. Чистая логика, покрыта тестами.
 * Список публичных суффиксов (для настоящего домена) — из OkHttp.
 */
object LinkCheck {

    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "intent", "data", "vbscript")

    private val SHORTENERS = setOf(
        "bit.ly", "clck.ru", "t.co", "vk.cc", "goo.su", "goo.gl", "tinyurl.com", "cutt.ly", "is.gd", "ow.ly", "u.to",
        "rb.gy", "shorturl.at", "tiny.cc", "s.id", "bit.do", "qrco.de", "lnkd.in", "buff.ly", "rebrand.ly", "t.ly",
        "v.gd", "clc.to", "surl.li", "y2u.be", "qps.ru", "to.click", "go.link",
    )

    /** Официальные домены, которые чаще всего подделывают (банки, Госуслуги, маркетплейсы, почта). */
    val KNOWN_DOMAINS = setOf(
        "sberbank.ru", "sber.ru", "sberbank.com", "gosuslugi.ru", "tinkoff.ru", "tbank.ru", "vtb.ru",
        "alfabank.ru", "gazprombank.ru", "raiffeisen.ru", "pochtabank.ru", "psbank.ru", "nalog.ru", "nalog.gov.ru",
        "mos.ru", "nspk.ru", "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "yandex.ru", "ya.ru", "yandex.com", "yandex.net", "mail.ru",
        "vk.com", "gmail.com", "google.com", "google.ru", "paypal.com", "apple.com", "icloud.com", "amazon.com", "microsoft.com",
    )

    /** Отличительные названия: встретились внутри чужого домена — подделка. Короткие и общие слова — только целым словом. */
    private val BRANDS_SUBSTRING = setOf(
        "sberbank", "sber", "gosuslugi", "tinkoff", "wildberries", "yandex", "paypal", "alfabank", "gazprombank",
        "raiffeisen", "pochtabank",
    )
    private val BRANDS_WORD = setOf("ozon", "avito", "vtb", "tbank", "apple", "icloud", "google", "gmail", "mailru", "nspk", "amazon", "microsoft", "nalog")

    /** Похожие символы → латинская «основа»: `0`→`o`, кириллическая `а`→`a` и т. п. */
    private val SKELETON = mapOf(
        '0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b',
        // Кириллица, похожая на латиницу: а в е ё к м н о р с т у х і ѕ.
        '\u0430' to 'a', '\u0432' to 'b', '\u0435' to 'e', '\u0451' to 'e', '\u043a' to 'k', '\u043c' to 'm', '\u043d' to 'h',
        '\u043e' to 'o', '\u0440' to 'p', '\u0441' to 'c', '\u0442' to 't', '\u0443' to 'y', '\u0445' to 'x', '\u0456' to 'l',
        '\u0455' to 's',
    )

    fun analyze(input: String): LinkInfo {
        val raw = input.trim()
        if (raw.isEmpty()) return LinkInfo(raw, LinkKind.UNKNOWN)
        if (raw.startsWith("ST0001", ignoreCase = true)) return gost(raw)
        if (raw.startsWith("WIFI:", ignoreCase = true)) return wifi(raw)

        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(raw)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == null -> if (looksLikeDomain(raw)) web(raw, "https://$raw") else LinkInfo(raw, LinkKind.UNKNOWN)
            scheme in BLOCKED_SCHEMES -> LinkInfo(raw, LinkKind.BLOCKED)
            scheme == "http" || scheme == "https" -> web(raw, raw)
            scheme == "tel" -> LinkInfo(raw, LinkKind.PHONE, openUri = raw)
            scheme == "sms" || scheme == "smsto" -> LinkInfo(raw, LinkKind.SMS, openUri = raw)
            scheme == "mailto" -> LinkInfo(raw, LinkKind.EMAIL, openUri = raw)
            // «host:port» без схемы распознался бы как схема — это всё же домен.
            looksLikeDomain(raw) && !raw.contains("://") && raw.substringAfter(':').takeWhile { it != '/' }.all(Char::isDigit) ->
                web(raw, "https://$raw")
            else -> LinkInfo(raw, LinkKind.OTHER_SCHEME, openUri = raw)
        }
    }

    private fun web(raw: String, address: String): LinkInfo {
        val url = address.toHttpUrlOrNull() ?: return LinkInfo(raw, LinkKind.UNKNOWN)
        val host = url.host
        val ip = isIp(host)
        val domain = if (ip) host else url.topPrivateDomain() ?: host
        val warnings = mutableSetOf<LinkWarning>()
        if (url.scheme == "http") warnings += LinkWarning.NO_HTTPS
        // HttpUrl переводит нелатинские домены в punycode — смесь латиницы и кириллицы тоже окажется здесь.
        if (host.split('.').any { it.startsWith("xn--") }) warnings += LinkWarning.LOOKALIKE_CHARS
        if (domain in SHORTENERS || host in SHORTENERS) warnings += LinkWarning.SHORTENER
        if (ip) warnings += LinkWarning.IP_ADDRESS
        if (hasUserInfo(address)) warnings += LinkWarning.AT_SIGN
        if (url.encodedPath.lowercase().endsWith(".apk")) warnings += LinkWarning.APK
        val similar = if (ip) null else similarKnown(host, domain, rawHost(address))
        if (similar != null) warnings += LinkWarning.SIMILAR_TO_KNOWN

        val sbp = !ip && (domain == "nspk.ru") && host.startsWith("qr.")
        return LinkInfo(
            raw = raw,
            kind = if (sbp) LinkKind.PAYMENT_SBP else LinkKind.WEB,
            openUri = address,
            host = host,
            domain = domain,
            warnings = warnings,
            similarTo = similar,
            payment = if (sbp) Payment(url.queryParameter("sum")?.toLongOrNull(), recipient = null) else null,
        )
    }

    /**
     * Известный домен, под который маскируется адрес, или null. Подделка — если домен не из списка, но
     * в хосте есть название бренда, или основа домена совпадает с известной после замены похожих символов,
     * или отличается от неё на 1–2 буквы.
     */
    fun similarKnown(host: String, domain: String, rawHost: String = host): String? {
        if (domain in KNOWN_DOMAINS) return null
        val labels = (rawHost.lowercase().split('.', '-') + host.split('.', '-')).filter { it.isNotEmpty() }
        for (label in labels) {
            val skeleton = skeleton(label)
            BRANDS_WORD.firstOrNull { it == label || it == skeleton }?.let { return knownFor(it) }
            BRANDS_SUBSTRING.firstOrNull { label.contains(it) || skeleton.contains(it) }?.let { return knownFor(it) }
        }
        // Та же основа в другой зоне (gosuslugi.com) или с подменой символов (0z0n) — подделка; опечатка (gosuslugl) —
        // только для длинных основ, иначе короткие честные домены (mall, oven) окажутся «похожими».
        val base = skeleton(domain.substringBefore('.'))
        for (known in KNOWN_DOMAINS) {
            val knownBase = skeleton(known.substringBefore('.'))
            if (knownBase.length < 3) continue
            if (base == knownBase) return known
            if (knownBase.length >= 6 && distance(base, knownBase) <= (if (knownBase.length >= 8) 2 else 1)) return known
        }
        return null
    }

    private fun knownFor(brand: String): String = when (brand) {
        "mailru" -> "mail.ru"
        "gmail" -> "gmail.com"
        "google" -> "google.com"
        "apple" -> "apple.com"
        "icloud" -> "icloud.com"
        "paypal" -> "paypal.com"
        "amazon" -> "amazon.com"
        "microsoft" -> "microsoft.com"
        "sber" -> "sberbank.ru"
        else -> "$brand.ru"
    }

    private fun skeleton(text: String): String = buildString { text.lowercase().forEach { append(SKELETON[it] ?: it) } }

    /** Расстояние Левенштейна. */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun authority(address: String): String =
        address.substringAfter("://", address).takeWhile { it != '/' && it != '?' && it != '#' }

    private fun hasUserInfo(address: String) = '@' in authority(address)

    /** Хост как он записан (до перевода в punycode) — по нему видно кириллицу. */
    private fun rawHost(address: String): String =
        authority(address).substringAfterLast('@').substringBefore(':').trim('[', ']')

    private fun isIp(host: String): Boolean =
        host.contains(':') || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.all { it.isDigit() }

    private fun looksLikeDomain(text: String): Boolean =
        Regex("""^(www\.)?[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)*\.\p{L}{2,}(:\d+)?([/?#]\S*)?$""").matches(text)

    /** Платёж по ГОСТ Р 56042: `ST00012|Name=…|PersonalAcc=…|BankName=…|Sum=15000|Purpose=…`. */
    private fun gost(raw: String): LinkInfo {
        val fields = raw.split('|').drop(1).mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).trim()
        }.toMap()
        return LinkInfo(
            raw,
            LinkKind.PAYMENT_GOST,
            payment = Payment(
                amountKopecks = fields["sum"]?.toLongOrNull(),
                recipient = fields["name"],
                bank = fields["bankname"],
                purpose = fields["purpose"],
            ),
        )
    }

    /** Wi-Fi: `WIFI:T:WPA;S:Сеть;P:пароль;H:false;;` — `\` экранирует `;`, `,`, `:` и `\`. */
    private fun wifi(raw: String): LinkInfo {
        val fields = mutableMapOf<String, String>()
        val body = raw.substring(5)
        var key = StringBuilder()
        var value = StringBuilder()
        var inValue = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    (if (inValue) value else key).append(body[i + 1])
                    i++
                }
                !inValue && c == ':' -> inValue = true
                c == ';' -> {
                    if (key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString()
                    key = StringBuilder()
                    value = StringBuilder()
                    inValue = false
                }
                else -> (if (inValue) value else key).append(c)
            }
            i++
        }
        if (key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString()
        val ssid = fields["S"] ?: return LinkInfo(raw, LinkKind.UNKNOWN)
        val password = fields["P"]?.takeIf { it.isNotEmpty() }
        return LinkInfo(
            raw,
            LinkKind.WIFI,
            openUri = raw,
            wifi = Wifi(ssid, fields["T"]?.takeIf { it.isNotEmpty() && !it.equals("nopass", true) }, password != null, password, fields["H"].equals("true", true)),
        )
    }

    private val TEXT_URL = Regex(
        """(?i)(?<![@\p{L}\p{N}./:-])((?:https?://|www\.)[^\s<>"'«»]+|[\p{L}\p{N}][\p{L}\p{N}-]*(?:\.[\p{L}\p{N}-]+)*\.(?:ru|su|com|net|org|info|io|me|xyz|top|site|online|shop|store|app|link|ly|cc|co|biz|pro|tr|de|uk|kz|by|ua|\u0440\u0444)(?:[/?#][^\s<>"'«»]*)?)(?![\p{L}\p{N}])""",
    )

    /** Адреса в распознанном тексте: со схемой, с `www.` и голые домены популярных зон; почтовые адреса — нет. */
    fun findInText(text: String): List<String> =
        TEXT_URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', ')', '!', '?') }.filter { it.contains('.') }.distinct().toList()

    /** Для источников веб-поиска и карточек: настоящий домен адреса или null. */
    fun domainOf(url: String): String? = (url.toHttpUrlOrNull() as HttpUrl?)?.let { it.topPrivateDomain() ?: it.host }
}
