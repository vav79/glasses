package ru.vasev.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.vasev.glasses.R
import ru.vasev.glasses.llm.KeyCheck
import ru.vasev.glasses.llm.KeyCheckResult
import ru.vasev.glasses.llm.ProviderId
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.theme.LocalSputnikColors

/**
 * Пользовательское соглашение. Первый запуск (и новая редакция): галочка и «Принять и продолжить»,
 * «Выйти» закрывает приложение. [readOnly] — из «О приложении»: только текст и «Назад».
 */
@Composable
fun AgreementScreen(readOnly: Boolean, onAccept: () -> Unit, onExit: () -> Unit) {
    val p = LocalSputnikColors.current
    var checked by rememberSaveable { mutableStateOf(false) }
    val text = rememberAgreementText()
    Column(Modifier.fillMaxSize().background(p.screen).safeDrawingPadding()) {
        Text(
            stringResource(R.string.agreement_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            AgreementText(text)
            Spacer(Modifier.size(16.dp))
        }
        HorizontalDivider(color = p.divider)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (readOnly) {
                GlassButton(stringResource(R.string.nav_back), onClick = onExit, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            // Закреплено внизу: галочка и две кнопки; «Принять» — только после галочки.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(stringResource(R.string.agreement_check), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(stringResource(R.string.agreement_exit), onClick = onExit, modifier = Modifier.weight(1f))
                GlassButton(
                    stringResource(R.string.agreement_accept),
                    onClick = onAccept,
                    enabled = checked,
                    kind = GlassButtonKind.PRIMARY,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

@Composable
private fun rememberAgreementText(): List<String> {
    val resources = LocalContext.current.resources
    return remember(resources) {
        resources.openRawResource(R.raw.agreement).bufferedReader(Charsets.UTF_8).use { it.readText() }.lines()
    }
}

/** Текст соглашения: «**…**» — заголовок раздела, «- » — пункт списка, пустая строка — абзац. */
@Composable
private fun AgreementText(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("**") && trimmed.endsWith("**") -> Text(
                    trimmed.removeSurrounding("**"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                trimmed.startsWith("- ") -> Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("•  ") }
                        append(trimmed.removePrefix("- "))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
                else -> Text(trimmed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Провайдер в списке выбора модели: Claude и DeepSeek — с ключом, остальные — «В разработке». */
private enum class SetupModel(val title: String, val provider: ProviderId?) {
    CLAUDE("Claude", ProviderId.ANTHROPIC),
    DEEPSEEK("DeepSeek", ProviderId.DEEPSEEK),
    CHATGPT("ChatGPT", null),
    GIGACHAT("GigaChat", null),
}

/**
 * Выбор модели при первом запуске (после соглашения): ключ вводится и сразу проверяется запросом.
 * [savedKeys] — провайдеры с ключом; [onSaveKey] сохраняет ключ (шифрует); [onDone] — дальше, в чат.
 */
@Composable
fun ModelSetupScreen(savedKeys: Set<ProviderId>, onSaveKey: (ProviderId, String) -> String?, onDone: () -> Unit) {
    val p = LocalSputnikColors.current
    var dialogFor by remember { mutableStateOf<SetupModel?>(null) }
    var inDevelopment by remember { mutableStateOf<SetupModel?>(null) }
    Column(Modifier.fillMaxSize().background(p.screen).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyMedium, color = p.text2)
        SetupModel.entries.forEach { model ->
            val hasKey = model.provider != null && model.provider in savedKeys
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .background(p.card, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { if (model.provider == null) inDevelopment = model else dialogFor = model }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            when {
                                model.provider == null -> R.string.setup_in_development
                                hasKey -> R.string.setup_key_added
                                else -> R.string.setup_enter_key
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasKey) p.ok else p.text2,
                    )
                }
                Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null, tint = p.text2)
            }
        }
        if (savedKeys.isEmpty()) {
            Text(stringResource(R.string.setup_warning), style = MaterialTheme.typography.bodyMedium, color = p.text2)
            GlassButton(stringResource(R.string.setup_skip), onClick = onDone, modifier = Modifier.fillMaxWidth())
        } else {
            GlassButton(stringResource(R.string.setup_continue), onClick = onDone, kind = GlassButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth())
        }
    }
    dialogFor?.let { model ->
        KeyDialog(model.title, model.provider!!, onSave = { onSaveKey(model.provider, it) }, onDismiss = { dialogFor = null })
    }
    inDevelopment?.let { model ->
        AlertDialog(
            onDismissRequest = { inDevelopment = null },
            containerColor = p.menu,
            title = { Text(model.title) },
            text = { Text(stringResource(R.string.setup_in_development_text)) },
            confirmButton = { TextButton(onClick = { inDevelopment = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/**
 * Ввод ключа с проверкой: бесплатный запрос списка моделей. Не подошёл — понятное сообщение; нет связи —
 * можно сохранить без проверки. [onSave] возвращает текст ошибки сохранения (формат ключа) или null.
 */
@Composable
fun KeyDialog(title: String, provider: ProviderId, onSave: (String) -> String?, onDismiss: () -> Unit) {
    val p = LocalSputnikColors.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KeyCheckResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        error = onSave(key)
        if (error == null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        containerColor = p.menu,
        title = { Text(stringResource(R.string.key_dialog_title, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        result = null
                        error = null
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.key_dialog_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.key_checking), color = p.text2)
                    }
                    error != null -> Text(error.orEmpty(), color = p.err)
                    result == KeyCheckResult.REJECTED -> Text(stringResource(R.string.key_rejected), color = p.err)
                    result == KeyCheckResult.NO_NETWORK -> Text(stringResource(R.string.key_no_network), color = p.warn)
                    result == KeyCheckResult.ERROR -> Text(stringResource(R.string.key_check_error), color = p.warn)
                }
                Text(stringResource(R.string.key_dialog_note), style = MaterialTheme.typography.bodySmall, color = p.text2)
            }
        },
        confirmButton = {
            // Нет связи или провайдер ответил ошибкой — можно сохранить без проверки; отклонён — нет.
            if (result == KeyCheckResult.NO_NETWORK || result == KeyCheckResult.ERROR) {
                TextButton(onClick = ::save) { Text(stringResource(R.string.key_save_anyway)) }
            } else {
                TextButton(
                    enabled = key.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val checked = KeyCheck.check(provider, key)
                            checking = false
                            result = checked
                            if (checked == KeyCheckResult.OK) save()
                        }
                    },
                ) { Text(stringResource(R.string.key_check_and_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(R.string.cancel)) } },
    )
}
