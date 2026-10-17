package ru.vasev.glasses.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.llm.ModelSpec

/** Подсказка вместо ошибки, когда для выбранной модели ещё не введён ключ провайдера. */
@Composable
fun MissingKeyNotice(model: ModelSpec, onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.missing_key_notice, model.title, model.provider.title),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.open_settings)) }
        }
    }
}
