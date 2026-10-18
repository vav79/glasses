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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.LanguageChoice
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
    val language by viewModel.language.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    val showAdvanced by viewModel.showAdvanced.collectAsStateWithLifecycle()
    val journalTexts by viewModel.journalTexts.collectAsStateWithLifecycle()
    val voiceStop by viewModel.voiceStop.collectAsStateWithLifecycle()
    val headsetMic by viewModel.headsetMic.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_app_language))
        LanguagePicker(language, viewModel::setLanguage)
        Hint(stringResource(R.string.settings_app_language_hint))

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_role))
        RolePicker(role, viewModel::setRole)
        Hint(stringResource(R.string.settings_role_hint))

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_headset))
        SwitchRow(
            title = stringResource(R.string.settings_voice_stop_title),
            subtitle = stringResource(R.string.settings_voice_stop_subtitle),
            checked = voiceStop,
            enabled = headsetMic,
            onChange = viewModel::setVoiceStop,
        )
        if (!headsetMic) Hint(stringResource(R.string.settings_voice_stop_no_headset))

        for (provider in viewModel.providers) {
            HorizontalDivider()
            KeySection(provider, keys.getValue(provider), viewModel)
        }
        Hint(stringResource(R.string.settings_keys_hint))

        // Расширенные: разработчику — сразу, остальным — по кнопке. Значения действуют независимо от видимости.
        HorizontalDivider()
        if (!role.advancedAlwaysVisible) {
            OutlinedButton(onClick = { viewModel.setShowAdvanced(!showAdvanced) }, modifier = Modifier.fillMaxWidth()) {
                IconLabel(R.drawable.ic_sym_tune, stringResource(if (showAdvanced) R.string.hide_advanced else R.string.show_advanced))
            }
        }
        if (!role.advancedAlwaysVisible && !showAdvanced) return@Column

        SectionTitle(stringResource(R.string.settings_advanced))
        SubsectionTitle(stringResource(R.string.settings_models))
        ModelPicker(stringResource(R.string.settings_model_assistant), assistantModel) { viewModel.setModel(Role.ASSISTANT, it) }
        ModelPicker(stringResource(R.string.settings_model_translation), translationModel) { viewModel.setModel(Role.TRANSLATION, it) }
        Hint(stringResource(R.string.settings_vision_hint))

        SubsectionTitle(stringResource(R.string.settings_fallback))
        SwitchRow(
            title = stringResource(R.string.settings_fallback_title),
            subtitle = stringResource(R.string.settings_fallback_subtitle),
            checked = fallbackEnabled,
            onChange = viewModel::setFallbackEnabled,
        )
        SwitchRow(
            title = stringResource(R.string.settings_fallback_photos_title),
            subtitle = stringResource(R.string.settings_fallback_photos_subtitle),
            checked = fallbackPhotos,
            enabled = fallbackEnabled,
            onChange = viewModel::setFallbackPhotos,
        )

        SubsectionTitle(stringResource(R.string.settings_journal))
        SwitchRow(
            title = stringResource(R.string.settings_journal_texts_title),
            subtitle = stringResource(R.string.settings_journal_texts_subtitle),
            checked = journalTexts,
            onChange = viewModel::setJournalTexts,
        )
    }
}

@Composable
private fun SubsectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun KeySection(provider: ProviderId, state: KeyState, viewModel: SettingsViewModel) {
    SectionTitle(stringResource(R.string.key_section_title, provider.title))
    val key = state.key
    if (key != null && !state.editing) {
        Text(stringResource(R.string.key_saved, key.tail), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Hint(stringResource(R.string.key_encrypted, if (key.strongBox) stringResource(R.string.key_strongbox_suffix) else ""))
        if (state.confirmDelete) {
            Status(stringResource(R.string.key_delete_confirm, provider.title), isError = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.delete(provider) }) { IconLabel(R.drawable.ic_sym_delete, stringResource(R.string.delete)) }
                OutlinedButton(onClick = { viewModel.cancelDelete(provider) }) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.startReplace(provider) }) { IconLabel(R.drawable.ic_sym_key, stringResource(R.string.key_replace)) }
                OutlinedButton(onClick = { viewModel.askDelete(provider) }) { IconLabel(R.drawable.ic_sym_delete, stringResource(R.string.key_delete)) }
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
                                if (model.vision) model.title else stringResource(R.string.model_no_photo, model.title),
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
        label = { Text(stringResource(R.string.key_paste, provider.title)) },
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
                    contentDescription = stringResource(if (visible) R.string.key_hide else R.string.key_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = submit, enabled = !busy && input.isNotBlank()) { Text(stringResource(R.string.save)) }
        if (canCancel) OutlinedButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cancel)) }
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

/** Системный / Русский / English. Смена языка пересоздаёт экран; история разговора и перевода сохраняются. */
@Composable
private fun LanguagePicker(selected: LanguageChoice, onSelect: (LanguageChoice) -> Unit) {
    val choices = LanguageChoice.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                // Без галочки: «Системный» иначе не помещается; выбранный сегмент и так выделен цветом.
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (choice) {
                            LanguageChoice.SYSTEM -> R.string.language_system
                            LanguageChoice.RUSSIAN -> R.string.language_native_russian
                            LanguageChoice.ENGLISH -> R.string.language_native_english
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
