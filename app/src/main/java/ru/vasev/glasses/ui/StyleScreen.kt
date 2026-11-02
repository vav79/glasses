package ru.vasev.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vasev.glasses.R
import ru.vasev.glasses.profile.AnswerStyle
import ru.vasev.glasses.profile.CommunicationStyle
import ru.vasev.glasses.profile.Formality
import ru.vasev.glasses.profile.SpeakerGender
import ru.vasev.glasses.profile.SpeechPace
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.GlassIconButton
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassContentSource

/** Подпись текущего стиля: «Кратко», «Кратко + своё». */
@Composable
fun styleSummary(style: CommunicationStyle): String {
    val name = stringResource(style.answer.title)
    return if (style.hasCustom) stringResource(R.string.style_summary_custom, name) else name
}

/**
 * «Стиль общения» — экран вместо содержимого вкладки (Настройки, «Указать» в «Переводе»), на том же стеклянном фоне.
 * Ничего не применяется до «Сохранить»; «Назад» с несохранёнными изменениями — вопрос «Сохранить?».
 * «Очистить» и «Сохранить» — стеклянная панель внизу, с клавиатурой — над клавиатурой.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StyleScreen(viewModel: StyleViewModel, onClose: () -> Unit) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val testQuestion by viewModel.testQuestion.collectAsStateWithLifecycle()
    var askSave by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.open() }

    fun back() {
        if (dirty) askSave = true else onClose()
    }
    BackHandler(onBack = ::back)

    // Панель кнопок — под прокручиваемой областью, а не поверх: с клавиатурой поле «Свой промт» прокручивается выше панели.
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .glassContentSource()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalTabBarInset.current)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(R.drawable.ic_sym_arrow_back, stringResource(R.string.nav_back), onClick = ::back, size = 48.dp)
                Text(
                    stringResource(R.string.settings_style_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 10.dp).semantics { heading() },
                )
            }

            Section(stringResource(R.string.style_section_assistant)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnswerStyle.entries.forEach { style ->
                        val isSelected = draft.answer == style
                        GlassButton(
                            stringResource(style.title),
                            onClick = { viewModel.edit { it.copy(answer = style) } },
                            kind = if (isSelected) GlassButtonKind.PRIMARY else GlassButtonKind.NORMAL,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                            modifier = Modifier.semantics { selected = isSelected },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.customPrompt,
                    onValueChange = viewModel::setCustom,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.style_custom_label)) },
                    placeholder = { Text(stringResource(R.string.style_custom_placeholder)) },
                    supportingText = {
                        Text(stringResource(R.string.style_custom_counter, draft.customPrompt.length, CommunicationStyle.MAX_CUSTOM))
                    },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(24.dp),
                )
                Hint(stringResource(R.string.style_custom_note))
            }

            // Обращение и пол — в настройках «Переводчика», скорость речи — в «Голосе и звуке» (В5).
            GlassCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Свой пробный вопрос; пусто — пример из ресурсов. Enter — «Проверить».
                OutlinedTextField(
                    value = testQuestion,
                    onValueChange = viewModel::setTestQuestion,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.style_test_label)) },
                    placeholder = { Text(stringResource(R.string.style_test_question)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!test.running && ready) viewModel.toggleTest() }),
                    shape = RoundedCornerShape(24.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassButton(
                        stringResource(if (test.running) R.string.style_test_cancel else R.string.style_test),
                        onClick = viewModel::toggleTest,
                        icon = if (test.running) R.drawable.ic_sym_stop else R.drawable.ic_sym_volume_up,
                        kind = GlassButtonKind.TONAL_TERTIARY,
                        enabled = ready || test.running,
                    )
                    if (test.running) CircularProgressIndicator(Modifier.size(24.dp), color = glassAccent(), strokeWidth = 3.dp)
                }
                Hint(stringResource(R.string.style_test_note))
                if (!ready && !test.running) Hint(stringResource(R.string.style_test_no_key))
                test.answer?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                test.details?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                test.tag?.takeIf(viewModel::showTag)?.let { Hint(modelLabel(it)) }
                test.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        }

        // Кнопки — под прокручиваемой областью, на фоне: содержимое под ними не проезжает.
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassButton(
                stringResource(R.string.style_clear),
                onClick = viewModel::clearCustom,
                enabled = draft.customPrompt.isNotEmpty(),
                minHeight = 56.dp,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                stringResource(R.string.style_save),
                onClick = {
                    viewModel.save()
                    onClose()
                },
                kind = GlassButtonKind.PRIMARY,
                enabled = dirty,
                minHeight = 56.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(stringResource(R.string.style_unsaved_title)) },
            text = { Text(stringResource(R.string.style_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askSave = false
                    viewModel.save()
                    onClose()
                }) { Text(stringResource(R.string.style_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSave = false }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        askSave = false
                        viewModel.discard()
                        onClose()
                    }) { Text(stringResource(R.string.style_unsaved_discard)) }
                }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Как «Язык приложения» в Настройках: без галочки, при крупном шрифте текст уменьшается, а не обрезается. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) {
                Text(
                    label(option),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant))
}

