package ru.vasev.glasses.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.glasses.GlassesStatus

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onAsk: () -> Unit,
    onWhatDoISee: () -> Unit,
    onConnectGlasses: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val busy = state.phase != Phase.IDLE

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Очки + Claude", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

            GlassesCard(
                status = status,
                enabled = !busy,
                onMockModeChange = viewModel::setMockMode,
                onConnectGlasses = onConnectGlasses,
            )

            if (!viewModel.hasApiKey) {
                Notice("Не задан API-ключ Anthropic. Впишите его в local.properties (anthropic_api_key) и пересоберите приложение.")
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.frame?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Кадр с очков",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                if (state.question.isNotEmpty()) Message("Вы", state.question)
                if (state.answer.isNotEmpty()) Message("Claude", state.answer)
                state.error?.let { Notice(it) }
                if (state.frame == null && state.question.isEmpty() && state.error == null) {
                    Text(
                        "Нажмите «Спросить» и задайте вопрос голосом. «Что я вижу» сначала снимет кадр с очков.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(state.phase.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::stop) { Text("Стоп") }
                }
            }

            Button(
                onClick = onAsk,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) { Text("Спросить", fontSize = 22.sp) }

            FilledTonalButton(
                onClick = onWhatDoISee,
                enabled = !busy && status.ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) { Text("Что я вижу", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun GlassesCard(
    status: GlassesStatus,
    enabled: Boolean,
    onMockModeChange: (Boolean) -> Unit,
    onConnectGlasses: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (status.mockMode) "Режим: симулятор" else "Режим: настоящие очки",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(status.text, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = status.mockMode, onCheckedChange = onMockModeChange, enabled = enabled)
            }
            if (!status.mockMode && !status.ready) {
                FilledTonalButton(onClick = onConnectGlasses, enabled = enabled) { Text("Подключить очки через Meta AI") }
            }
        }
    }
}

@Composable
private fun Message(author: String, text: String) {
    Column {
        Text(author, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
