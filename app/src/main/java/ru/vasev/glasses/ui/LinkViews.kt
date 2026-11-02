package ru.vasev.glasses.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import ru.vasev.glasses.R
import ru.vasev.glasses.links.FoundLink
import ru.vasev.glasses.links.LinkInfo
import ru.vasev.glasses.links.LinkKind
import ru.vasev.glasses.links.LinkTexts
import ru.vasev.glasses.links.LinkWarning
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard

/** Что крупно на карточке: настоящий домен, сеть Wi-Fi, номер или «Оплата». */
@Composable
private fun headline(info: LinkInfo): String = when (info.kind) {
    LinkKind.WEB -> info.domain.orEmpty()
    LinkKind.PAYMENT_SBP, LinkKind.PAYMENT_GOST -> stringResource(R.string.link_pay_title) +
        (info.payment?.amountKopecks?.let { " · " + LinkTexts.amount(it) } ?: "")
    LinkKind.WIFI -> info.wifi?.ssid.orEmpty()
    LinkKind.PHONE, LinkKind.SMS, LinkKind.EMAIL -> info.raw.substringAfter(':').substringBefore('?')
    else -> info.raw.substringBefore(':') + ":"
}

/** Ссылка в чате под снимком: домен крупно и значок предупреждения. Сама ничего не открывает — только карточку проверки. */
@Composable
fun LinkChip(link: FoundLink, onClick: () -> Unit) {
    val info = link.info
    val danger = info.warnings.isNotEmpty() || info.kind == LinkKind.BLOCKED
    val c = MaterialTheme.colorScheme
    val title = headline(info)
    GlassCard(
        Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.link_open_label, title), onClick = onClick),
        tint = if (danger) c.errorContainer else c.surface,
        text = if (danger) c.onErrorContainer else c.onSurface,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(if (link.fromQr) R.string.link_title_qr else R.string.link_title_text) +
                if (danger) " · ⚠ " + info.warnings.size.coerceAtLeast(1) else "",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Проверка перед открытием: настоящий домен крупно, полный адрес, предупреждения, для оплаты — сумма, получатель
 * и «проверьте получателя в банке», для .apk — строгое предупреждение. Открыть — только кнопкой (голосом —
 * пока карточка на экране и ссылка без предупреждений, см. [AssistantEngine.requestLink]).
 */
@Composable
fun LinkConfirmDialog(link: FoundLink, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val info = link.info
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        // Непрозрачная: окно поверх приложения, и читать адрес нужно без помех.
        Column(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(c.surfaceContainerHigh)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (link.fromQr) R.string.link_title_qr else R.string.link_title_text),
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurfaceVariant,
            )
            if (info.kind == LinkKind.WEB) Text(stringResource(R.string.link_site), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            Text(
                headline(info),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = c.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            info.payment?.let { pay ->
                if (info.kind == LinkKind.PAYMENT_SBP) Line(stringResource(R.string.link_pay_sbp))
                Line(pay.amountKopecks?.let { stringResource(R.string.link_pay_amount, LinkTexts.amount(it)) } ?: stringResource(R.string.link_pay_amount_none))
                Line(pay.recipient?.let { stringResource(R.string.link_pay_recipient, it) } ?: stringResource(R.string.link_pay_recipient_unknown))
                pay.bank?.let { Line(stringResource(R.string.link_pay_bank, it)) }
                pay.purpose?.let { Line(stringResource(R.string.link_pay_purpose, it)) }
                Alert(stringResource(R.string.link_pay_check))
            }
            info.wifi?.let { wifi ->
                Line(stringResource(R.string.link_wifi, wifi.ssid))
                Line(stringResource(if (wifi.hasPassword) R.string.link_wifi_password else R.string.link_wifi_open))
            }
            if (info.kind == LinkKind.OTHER_SCHEME) Line(stringResource(R.string.link_other_scheme, info.raw.substringBefore(':') + ":"))

            if (info.warnings.isNotEmpty()) {
                Text(stringResource(R.string.link_warnings_title), style = MaterialTheme.typography.titleSmall, color = c.error)
                info.warnings.forEach { Alert("⚠ " + LinkTexts.warning(context, info, it)) }
            }
            if (LinkWarning.APK in info.warnings) Alert(stringResource(R.string.link_apk_strict))
            if (info.kind == LinkKind.BLOCKED) Alert(stringResource(R.string.link_blocked))
            if (info.kind == LinkKind.PAYMENT_GOST) Line(stringResource(R.string.link_gost_no_open))

            // Полный адрес — моноширинным: подмена символов видна; можно выделить и скопировать.
            if (info.kind != LinkKind.PAYMENT_GOST && info.kind != LinkKind.WIFI) {
                Text(stringResource(R.string.link_full_address), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                SelectionContainer {
                    Text(info.raw, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = c.onSurface)
                }
            }

            if (info.openable) {
                Text(
                    stringResource(if (info.voiceConfirmAllowed) R.string.link_voice_ok else R.string.link_button_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.openable) {
                    val label = when (info.kind) {
                        LinkKind.PAYMENT_SBP -> R.string.link_open_bank
                        LinkKind.WIFI -> R.string.link_wifi_connect
                        LinkKind.PHONE -> R.string.link_dial
                        LinkKind.SMS, LinkKind.EMAIL -> R.string.link_write
                        LinkKind.OTHER_SCHEME -> R.string.link_open_app
                        else -> if (info.warnings.isEmpty()) R.string.link_open else R.string.link_open_anyway
                    }
                    GlassButton(
                        stringResource(label),
                        onClick = onOpen,
                        kind = if (info.warnings.isEmpty() && info.kind == LinkKind.WEB) GlassButtonKind.PRIMARY else GlassButtonKind.ERROR,
                    )
                }
                GlassButton(stringResource(R.string.cancel), onClick = onDismiss, kind = if (info.warnings.isEmpty()) GlassButtonKind.NORMAL else GlassButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Alert(text: String) {
    val c = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = c.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.errorContainer).padding(10.dp),
    )
}

/** Подпись под ссылкой-источником: настоящий домен виден всегда. */
fun sourceDomain(url: String): String = ru.vasev.glasses.links.LinkCheck.domainOf(url) ?: url
