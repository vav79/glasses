package ru.vasev.glasses.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ProviderId
import ru.vasev.glasses.llm.Role

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val assistantModel by viewModel.assistantModel.collectAsStateWithLifecycle()
    val translationModel by viewModel.translationModel.collectAsStateWithLifecycle()
    val fallbackEnabled by viewModel.fallbackEnabled.collectAsStateWithLifecycle()
    val fallbackPhotos by viewModel.fallbackPhotos.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Модели")
        ModelPicker("Ассистент", assistantModel) { viewModel.setModel(Role.ASSISTANT, it) }
        ModelPicker("Перевод", translationModel) { viewModel.setModel(Role.TRANSLATION, it) }
        Hint("Если у модели нет зрения, для «Что я вижу» автоматически берётся DeepSeek Flash.")

        HorizontalDivider()
        SectionTitle("Запасная модель")
        SwitchRow(
            title = "Повторять через DeepSeek Flash, если Claude недоступен",
            subtitle = "При ошибках 403, 429, 5xx и обрывах связи — один повтор. Без интернета не пробует.",
            checked = fallbackEnabled,
            onChange = viewModel::setFallbackEnabled,
        )
        SwitchRow(
            title = "Отправлять фото в запасную модель",
            subtitle = "Выключено: «Что я вижу» при сбое Claude не отправит снимок в DeepSeek, " +
                "а снимки из истории уйдут пометкой «[фото]».",
            checked = fallbackPhotos,
            enabled = fallbackEnabled,
            onChange = viewModel::setFallbackPhotos,
        )

        for (provider in viewModel.providers) {
            HorizontalDivider()
            KeySection(provider, keys.getValue(provider), viewModel)
        }

        Hint(
            "Ключи создаются в консолях провайдеров (console.anthropic.com, platform.deepseek.com). Они хранятся " +
                "только на этом телефоне в зашифрованном виде, в сборку приложения не входят, а резервное " +
                "копирование данных приложения отключено.",
        )
    }
}

@Composable
private fun KeySection(provider: ProviderId, state: KeyState, viewModel: SettingsViewModel) {
    SectionTitle("API-ключ ${provider.title}")
    val key = state.key
    if (key != null && !state.editing) {
        Text("Ключ сохранён: ••••${key.tail}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Hint(
            "Зашифрован AES-GCM ключом из Android Keystore" + (if (key.strongBox) " (StrongBox)" else "") +
                ". Расшифровывается только в памяти перед запросом.",
        )
        if (state.confirmDelete) {
            Status("Удалить ключ ${provider.title}? Модели ${provider.title} перестанут работать.", isError = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.delete(provider) }) { IconLabel(R.drawable.ic_sym_delete, "Удалить") }
                OutlinedButton(onClick = { viewModel.cancelDelete(provider) }) { Text("Отмена") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.startReplace(provider) }) { IconLabel(R.drawable.ic_sym_key, "Заменить ключ") }
                OutlinedButton(onClick = { viewModel.askDelete(provider) }) { IconLabel(R.drawable.ic_sym_delete, "Удалить ключ") }
            }
        }
    } else {
        KeyEditor(
            provider = provider,
            busy = state.busy,
            canCancel = key != null,
            onSave = { viewModel.save(provider, it) },
            onCancel = { viewModel.cancelReplace(provider) },
        )
    }
    state.error?.let { Status(it, isError = true) }
    state.message?.let { Status(it, isError = false) }
}

@Composable
private fun ModelPicker(role: String, selected: ModelSpec, onSelect: (ModelSpec) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(role, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("${selected.title} ▾", maxLines = 1) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ModelCatalog.all.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                model.title + if (model.vision) "" else " (без фото)",
                                fontWeight = if (model == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(model)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // Вся строка — один переключатель: TalkBack читает заголовок, пояснение и состояние вместе.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = SemanticsRole.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun KeyEditor(provider: ProviderId, busy: Boolean, canCancel: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
    // Обычный remember, не rememberSaveable: введённый ключ не должен попадать в сохранённое состояние Activity.
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val submit = {
        onSave(input)
        input = ""
    }

    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Вставьте ключ ${provider.title}") },
        singleLine = true,
        enabled = !busy,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) submit() }),
        trailingIcon = {
            // IconButton — зона нажатия 48 dp; подпись для TalkBack меняется вместе с состоянием.
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_sym_visibility_off else R.drawable.ic_sym_visibility),
                    contentDescription = if (visible) "Скрыть ключ" else "Показать ключ",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = submit, enabled = !busy && input.isNotBlank()) { Text("Сохранить") }
        if (canCancel) OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Отмена") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Status(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Иконка + подпись внутри кнопки; иконка декоративная, кнопку озвучивает подпись. */
@Composable
private fun IconLabel(@DrawableRes icon: Int, label: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.size(8.dp))
    Text(label)
}
