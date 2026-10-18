package ru.vasev.glasses.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import ru.vasev.glasses.R

/**
 * Открытие ссылки — только после подтверждения пользователя. Сайты — во внешнем браузере (Custom Tabs), адрес как
 * есть, ничего от приложения к нему не добавляется. Телефон — только набрать (звонит пользователь), Wi-Fi —
 * системное окно добавления сети. Запрещённые схемы и реквизиты ГОСТ не открываются никогда.
 */
object LinkOpener {

    fun open(context: Context, info: LinkInfo) {
        if (!info.openable) return
        val uri = Uri.parse(info.openUri ?: return)
        Log.d(TAG, "open kind=${info.kind} domain=${info.domain} warnings=${info.warnings}")
        when (info.kind) {
            LinkKind.WEB -> browser(context, uri)
            // Оплата СБП: обычный просмотр — ссылку подхватит приложение банка (в Custom Tabs она осталась бы в браузере).
            LinkKind.PAYMENT_SBP -> start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            LinkKind.PHONE -> start(context, Intent(Intent.ACTION_DIAL, uri))
            LinkKind.SMS, LinkKind.EMAIL -> start(context, Intent(Intent.ACTION_SENDTO, uri))
            LinkKind.WIFI -> wifi(context, info.wifi ?: return)
            // Только то, что браузер и так вправе открыть по ссылке: CATEGORY_BROWSABLE.
            LinkKind.OTHER_SCHEME -> start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            LinkKind.PAYMENT_GOST, LinkKind.BLOCKED, LinkKind.UNKNOWN -> Unit
        }
    }

    /** Источник из веб-поиска: нажатие на кнопку — уже подтверждение; открывается так же, через Custom Tabs. */
    fun openSource(context: Context, url: String) {
        val info = LinkCheck.analyze(url)
        if (info.kind == LinkKind.WEB) browser(context, Uri.parse(info.openUri))
    }

    private fun browser(context: Context, uri: Uri) {
        val tabs = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setShowTitle(true)
            .build()
        tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tabs.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun wifi(context: Context, wifi: Wifi) {
        val suggestion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).apply {
                    val password = wifi.password
                    when {
                        password == null -> Unit
                        wifi.security.equals("SAE", true) || wifi.security.equals("WPA3", true) -> setWpa3Passphrase(password)
                        wifi.security.equals("WEP", true) -> error("WEP is not supported")
                        else -> setWpa2Passphrase(password)
                    }
                    if (wifi.hidden) setIsHiddenSsid(true)
                }.build()
            }.getOrNull()
        } else {
            null
        }
        if (suggestion != null) {
            // Android сам спросит, добавить ли сеть.
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
            start(context, intent)
        } else {
            start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.link_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private const val TAG = "Links"
}
