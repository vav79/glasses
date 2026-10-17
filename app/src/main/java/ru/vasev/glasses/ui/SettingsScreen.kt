package ru.vasev.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("API-ключ Anthropic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        val key = state.key
        if (key != null && !state.editing) {
            Text("Ключ сохранён: ••••${key.tail}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            Text(
                "Зашифрован AES-GCM ключом из Android Keystore" +
                    (if (key.strongBox) " (StrongBox)" else "") +
                    ". Расшифровывается только в памяти перед запросом к Claude.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.confirmDelete) {
                Status(
                    "Удалить ключ? Без него ассистент и перевод работать не будут.",
                    isError = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::delete) { Text("Удалить") }
                    OutlinedButton(onClick = viewModel::cancelDelete) { Text("Отмена") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::startReplace) { Text("Заменить ключ") }
                    OutlinedButton(onClick = viewModel::askDelete) { Text("Удалить ключ") }
                }
            }
        } else {
            KeyEditor(
                busy = state.busy,
                canCancel = key != null,
                onSave = viewModel::save,
                onCancel = viewModel::cancelReplace,
            )
        }

        state.error?.let { Status(it, isError = true) }
        state.message?.let { Status(it, isError = false) }

        Text(
            "Ключ создаётся на console.anthropic.com → API Keys. Он хранится только на этом телефоне, " +
                "в сборку приложения не входит, а резервное копирование данных приложения отключено.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyEditor(busy: Boolean, canCancel: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
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
        label = { Text("Вставьте API-ключ") },
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
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Скрыть" else "Показать") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = submit, enabled = !busy && input.isNotBlank()) { Text("Сохранить") }
        if (canCancel) OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Отмена") }
    }
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
