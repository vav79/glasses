package ru.vasev.glasses.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassCard

/** Подсказка вместо ошибки, когда для выбранной модели ещё не введён ключ провайдера. */
@Composable
fun MissingKeyNotice(model: ModelSpec, onOpenSettings: () -> Unit) {
    val c = MaterialTheme.colorScheme
    GlassCard(
        Modifier.fillMaxWidth(),
        tint = c.secondaryContainer,
        text = c.onSecondaryContainer,
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(
            stringResource(R.string.missing_key_notice, model.title, model.provider.title),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.align(Alignment.End)) {
            GlassButton(stringResource(R.string.open_settings), onClick = onOpenSettings)
        }
    }
}
