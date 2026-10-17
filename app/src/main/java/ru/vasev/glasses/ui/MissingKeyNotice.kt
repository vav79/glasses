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
import androidx.compose.ui.unit.dp

/** Подсказка вместо ошибки, когда API-ключ ещё не введён. */
@Composable
fun MissingKeyNotice(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Чтобы задавать вопросы Claude и переводить, нужен API-ключ Anthropic. " +
                    "Он хранится только на этом телефоне в зашифрованном виде.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) { Text("Ввести ключ") }
        }
    }
}
