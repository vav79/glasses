package ru.vasev.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.i18n.AppLanguage
import ru.vasev.glasses.i18n.LanguageChoice
import ru.vasev.glasses.profile.UserRole

/**
 * Первый запуск: язык, затем роль. Шаг и выбранная роль переживают пересоздание Activity —
 * смена языка на первом шаге сразу перерисовывает экран на новом языке.
 */
@Composable
fun OnboardingScreen(
    language: LanguageChoice,
    systemLanguage: AppLanguage,
    onLanguage: (LanguageChoice) -> Unit,
    initialRole: UserRole,
    onFinish: (UserRole) -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var roleName by rememberSaveable { mutableStateOf(initialRole.name) }
    val role = UserRole.restore(roleName)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (step == 0) {
                    Title(stringResource(R.string.onboarding_language_title))
                    Text(stringResource(R.string.onboarding_language_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.selectableGroup()) {
                        LanguageChoice.entries.forEach { choice ->
                            LanguageRow(
                                title = when (choice) {
                                    LanguageChoice.SYSTEM -> stringResource(R.string.language_system_current, nativeName(systemLanguage))
                                    LanguageChoice.RUSSIAN -> stringResource(R.string.language_native_russian)
                                    LanguageChoice.ENGLISH -> stringResource(R.string.language_native_english)
                                },
                                selected = choice == language,
                                onClick = { if (choice != language) onLanguage(choice) },
                            )
                        }
                    }
                } else {
                    Title(stringResource(R.string.onboarding_role_title))
                    Text(stringResource(R.string.onboarding_role_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RolePicker(role) { roleName = it.name }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 1) {
                    OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.back)) }
                }
                Button(
                    onClick = { if (step == 0) step = 1 else onFinish(role) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (step == 0) R.string.next else R.string.start)) }
            }
        }
    }
}

@Composable
private fun nativeName(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.RUSSIAN -> R.string.language_native_russian
        AppLanguage.ENGLISH -> R.string.language_native_english
    },
)

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun LanguageRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = SemanticsRole.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** Три карточки ролей: иконка, название, строка описания. Используется и в первом запуске, и в настройках. */
@Composable
fun RolePicker(selected: UserRole, onSelect: (UserRole) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UserRole.entries.forEach { role -> RoleCard(role, role == selected) { onSelect(role) } }
    }
}

@Composable
private fun RoleCard(role: UserRole, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainer,
            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape)
            .selectable(selected = selected, role = SemanticsRole.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (selected) colors.primary else colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(role.icon),
                    contentDescription = null,
                    tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(role.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(role.descriptionRes), style = MaterialTheme.typography.bodyMedium)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
