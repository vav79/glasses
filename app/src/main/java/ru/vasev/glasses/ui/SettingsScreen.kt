package ru.vasev.glasses.ui

import androidx.activity.compose.BackHandler
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.ui.theme.AppearanceSettings
import ru.vasev.glasses.ui.theme.ThemeMode
import ru.vasev.glasses.speech.MicChoice
import ru.vasev.glasses.profile.SpeechPace
import ru.vasev.glasses.i18n.LanguageChoice
import ru.vasev.glasses.llm.assistantName
import ru.vasev.glasses.llm.AiRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ru.vasev.glasses.speech.GesturesService
import ru.vasev.glasses.speech.Tap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.platform.LocalConfiguration
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
import ru.vasev.glasses.rates.CurrencySettings
import ru.vasev.glasses.ui.glass.GlassButton
import ru.vasev.glasses.ui.glass.GlassButtonKind
import ru.vasev.glasses.ui.glass.GlassCard
import ru.vasev.glasses.ui.glass.glassAccent
import ru.vasev.glasses.ui.glass.glassBackgroundText
import ru.vasev.glasses.ui.glass.glassContentSource
import ru.vasev.glasses.llm.ModelCatalog
import ru.vasev.glasses.llm.ModelSpec
import ru.vasev.glasses.llm.ProviderId
import ru.vasev.glasses.llm.Role
import ru.vasev.glasses.nav.CountryLanguage
import ru.vasev.glasses.nav.NavigatorChoice
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenDeveloper: () -> Unit, onOpenVoices: () -> Unit, startPage: SettingsPage? = null) {
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
    val gestureEnabled by viewModel.gestureEnabled.collectAsStateWithLifecycle()
    val gestureTap by viewModel.gestureTap.collectAsStateWithLifecycle()
    val gestureState by viewModel.gestureState.collectAsStateWithLifecycle()
    val themeMode by viewModel.theme.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val currency by viewModel.currency.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val navigator by viewModel.navigator.collectAsStateWithLifecycle()
    val taxiLanguage by viewModel.taxiLanguage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val styleViewModel: StyleViewModel = viewModel()
    val style by styleViewModel.saved.collectAsStateWithLifecycle()
    var styleOpen by rememberSaveable { mutableStateOf(false) }
    var agreementOpen by rememberSaveable { mutableStateOf(false) }
    // Текст соглашения из «О приложении» — вместо настроек, «Назад» возвращает к ним.
    if (agreementOpen) {
        BackHandler { agreementOpen = false }
        AgreementScreen(readOnly = true, onAccept = {}, onExit = { agreementOpen = false })
        return
    }
    // «Стиль общения» — вместо содержимого настроек, на том же стеклянном фоне.
    if (styleOpen) {
        StyleScreen(styleViewModel) { styleOpen = false }
        return
    }

    val micChoice by viewModel.micChoice.collectAsStateWithLifecycle()
    val devUnlocked by viewModel.devUnlocked.collectAsStateWithLifecycle()
    val allLabels by viewModel.allModelLabels.collectAsStateWithLifecycle()
    var page by rememberSaveable(startPage) { mutableStateOf(startPage) }
    // Раздел открыт — «Назад» возвращает к списку разделов, а не в чат.
    if (page != null) BackHandler { page = null }
    when (page) {
        null -> SettingsRoot(
            summaries = mapOf(
                SettingsPage.LANGUAGE to stringResource(
                    when (language) {
                        LanguageChoice.SYSTEM -> R.string.language_system
                        LanguageChoice.RUSSIAN -> R.string.language_native_russian
                        LanguageChoice.ENGLISH -> R.string.language_native_english
                    },
                ),
                SettingsPage.THEME to stringResource(
                    when (themeMode) {
                        ThemeMode.DARK -> R.string.theme_dark
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.SYSTEM -> R.string.theme_system
                    },
                ),
                SettingsPage.FONT to "$fontScale %",
                SettingsPage.AI to viewModel.providers.filter { keys.getValue(it).key != null }.joinToString { it.assistantName }
                    .ifEmpty { stringResource(R.string.model_status_no_key) },
                SettingsPage.PERSONAL to styleSummary(style),
                SettingsPage.VOICE to stringResource(micChoice.titleRes),
                SettingsPage.REGIONAL to currency,
                SettingsPage.NAVIGATION to stringResource(
                    when (navigator) {
                        NavigatorChoice.AUTO -> R.string.navigator_auto
                        NavigatorChoice.YANDEX -> R.string.maps_yandex_short
                        NavigatorChoice.GOOGLE -> R.string.maps_google_short
                    },
                ),
                SettingsPage.ABOUT to BuildConfig.VERSION_NAME,
            ),
            showDeveloper = devUnlocked,
            onOpen = { page = it },
        )
        SettingsPage.PERSONAL -> StyleScreen(styleViewModel) { page = null }
        else -> SettingsPageFrame(stringResource(page!!.titleRes)) {
            when (page!!) {
                SettingsPage.LANGUAGE -> {
                    LanguagePicker(language, viewModel::setLanguage)
                    Hint(stringResource(R.string.settings_app_language_hint))
                }
                SettingsPage.THEME -> ThemePicker(themeMode, viewModel::setTheme)
                SettingsPage.FONT -> {
                    FontScalePicker(fontScale, viewModel::setFontScale)
                    Hint(stringResource(R.string.settings_font_hint))
                }
                SettingsPage.AI -> {
                    val photoProvider by viewModel.photoProvider.collectAsStateWithLifecycle()
                    val withKeys = viewModel.providers.filter { keys.getValue(it).key != null }
                    Section(stringResource(R.string.settings_roles)) {
                        if (withKeys.isEmpty()) {
                            Hint(stringResource(R.string.settings_roles_no_keys))
                        } else {
                            RoleRow(stringResource(R.string.ai_role_text), withKeys, assistantModel.provider) { viewModel.setRoleProvider(AiRole.TEXT, it) }
                            RoleRow(stringResource(R.string.ai_role_photo), withKeys, photoProvider ?: assistantModel.provider, sameAsText = photoProvider == null) {
                                viewModel.setPhotoProvider(if (it == assistantModel.provider) null else it)
                            }
                            RoleRow(stringResource(R.string.ai_role_translation), withKeys, translationModel.provider) { viewModel.setRoleProvider(AiRole.TRANSLATION, it) }
                            Hint(stringResource(R.string.settings_roles_hint))
                        }
                    }
                    for (provider in viewModel.providers) {
                        Section(stringResource(R.string.key_section_title, provider.assistantName)) {
                            KeySection(provider, keys.getValue(provider), viewModel)
                        }
                    }
                    BackgroundHint(stringResource(R.string.settings_keys_hint))
                    Section(stringResource(R.string.settings_fallback)) {
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
                    }
                }
                SettingsPage.COSTS -> CostsSection(viewModel)
                SettingsPage.VOICE -> {
                    Section(stringResource(R.string.glasses_mic)) {
                        MicPicker(micChoice, viewModel::setMicChoice)
                        Hint(stringResource(R.string.mic_auto_hint))
                    }
                    Section(stringResource(R.string.settings_headset)) {
                        SwitchRow(
                            title = stringResource(R.string.settings_voice_stop_title),
                            subtitle = stringResource(R.string.settings_voice_stop_subtitle),
                            checked = voiceStop,
                            enabled = headsetMic,
                            onChange = viewModel::setVoiceStop,
                        )
                        if (!headsetMic) Hint(stringResource(R.string.settings_voice_stop_no_headset))
                    }
                    Section(stringResource(R.string.style_section_pace)) {
                        PacePicker(style.pace) { styleViewModel.savePace(it) }
                        Hint(stringResource(R.string.style_pace_hint))
                    }
                    Section(stringResource(R.string.settings_tts_voices)) {
                        Hint(stringResource(R.string.settings_tts_voices_hint))
                        GlassButton(stringResource(R.string.download_voice), onClick = onOpenVoices, icon = R.drawable.ic_sym_download, modifier = Modifier.fillMaxWidth())
                    }
                }
                SettingsPage.REGIONAL -> {
                    Section(stringResource(R.string.settings_my_language)) {
                        Text(stringResource(R.string.settings_my_language_value, stringResource(viewModel.myLanguageTitle)), style = MaterialTheme.typography.bodyLarge)
                        Hint(stringResource(R.string.settings_my_language_hint))
                    }
                    Section(stringResource(R.string.settings_currency_title)) {
                        CurrencyPicker(currency, viewModel::setCurrency)
                        Hint(stringResource(R.string.settings_currency_subtitle))
                        Hint(
                            rates?.let { stringResource(R.string.settings_rates_cached, RATE_DATE.format(it.table.date), it.source) }
                                ?: stringResource(R.string.settings_rates_none),
                        )
                    }
                    val country by viewModel.countryOfStay.collectAsStateWithLifecycle()
                    Section(stringResource(R.string.settings_country)) {
                        Text(country?.let { java.util.Locale("", it).getDisplayCountry(LocalConfiguration.current.locales[0]) } ?: stringResource(R.string.settings_country_unknown), style = MaterialTheme.typography.bodyLarge)
                        Hint(stringResource(R.string.settings_country_hint))
                    }
                }
                SettingsPage.NAVIGATION -> {
                    NavigatorPicker(navigator, viewModel::setNavigator)
                    Hint(stringResource(R.string.settings_navigator_hint))
                    Text(stringResource(R.string.settings_taxi_language), style = MaterialTheme.typography.bodyLarge)
                    TaxiLanguagePicker(taxiLanguage, viewModel::setTaxiLanguage)
                    Hint(stringResource(R.string.settings_taxi_language_hint))
                }
                SettingsPage.ABOUT -> {
                    if (agreementOpen) return@SettingsPageFrame
                    Section(stringResource(R.string.app_full_name)) {
                        // 7 нажатий на версию — раздел «Для разработчика» (как «Параметры разработчика» в Android).
                        var taps by remember { mutableStateOf(0) }
                        val unlockedText = stringResource(R.string.settings_developer_unlocked)
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.heightIn(min = 48.dp).clickable {
                                taps++
                                if (taps >= DEV_TAPS && !devUnlocked) {
                                    viewModel.unlockDeveloper()
                                    android.widget.Toast.makeText(context, unlockedText, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        Hint(stringResource(R.string.about_developer, stringResource(R.string.about_developer_name)))
                        viewModel.agreementAccepted?.let { Hint(it) }
                        GlassButton(stringResource(R.string.agreement_title), onClick = { agreementOpen = true }, modifier = Modifier.fillMaxWidth())
                    }
                }
                SettingsPage.DEVELOPER -> {
                    Section(stringResource(R.string.settings_models)) {
                        ModelPicker(stringResource(R.string.settings_model_assistant), assistantModel, markNoSearch = true) { viewModel.setModel(Role.ASSISTANT, it) }
                        ModelPicker(stringResource(R.string.settings_model_translation), translationModel, markNoSearch = false) { viewModel.setModel(Role.TRANSLATION, it) }
                        if (!assistantModel.webSearch) Hint("${assistantModel.title}: ${stringResource(R.string.model_no_web_search)}")
                        Hint(stringResource(R.string.settings_vision_hint))
                    }
                    Section(stringResource(R.string.settings_journal)) {
                        SwitchRow(
                            title = stringResource(R.string.settings_model_labels_title),
                            subtitle = stringResource(R.string.settings_model_labels_subtitle),
                            checked = allLabels,
                            onChange = viewModel::setAllModelLabels,
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_journal_texts_title),
                            subtitle = stringResource(R.string.settings_journal_texts_subtitle),
                            checked = journalTexts,
                            onChange = viewModel::setJournalTexts,
                        )
                        GlassButton(stringResource(R.string.dev_journal_title), onClick = onOpenDeveloper, icon = R.drawable.ic_sym_code, modifier = Modifier.fillMaxWidth())
                    }
                }
                SettingsPage.PERSONAL -> Unit
            }
        }
    }
}

/** Разделы настроек по заданию; «Для разработчика» — скрытый, открывается 7 нажатиями на версию. */
enum class SettingsPage(@androidx.annotation.StringRes val titleRes: Int, @androidx.annotation.DrawableRes val icon: Int) {
    LANGUAGE(R.string.settings_page_language, R.drawable.ic_sym_translate),
    THEME(R.string.settings_theme, R.drawable.ic_sym_visibility),
    FONT(R.string.settings_page_font, R.drawable.ic_sym_keyboard),
    AI(R.string.settings_page_ai, R.drawable.ic_sym_assistant),
    COSTS(R.string.settings_page_costs, R.drawable.ic_sym_history),
    PERSONAL(R.string.settings_page_personal, R.drawable.ic_sym_tune),
    VOICE(R.string.settings_page_voice, R.drawable.ic_sym_volume_up),
    REGIONAL(R.string.settings_page_regional, R.drawable.ic_sym_explore),
    NAVIGATION(R.string.settings_navigation, R.drawable.ic_sym_navigation),
    ABOUT(R.string.about_title, R.drawable.ic_sym_sticky_note_2),
    DEVELOPER(R.string.settings_developer, R.drawable.ic_sym_code),
}

/** Список разделов в духе настроек Meta AI: иконка, название, текущее значение, ›. */
@Composable
private fun SettingsRoot(summaries: Map<SettingsPage, String>, showDeveloper: Boolean, onOpen: (SettingsPage) -> Unit) {
    val p = ru.vasev.glasses.ui.theme.LocalSputnikColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsPage.entries.filter { it != SettingsPage.DEVELOPER || showDeveloper }.forEach { page ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = SemanticsRole.Button) { onOpen(page) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(page.icon), contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(stringResource(page.titleRes), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    summaries[page]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.text2, maxLines = 1) }
                }
                Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null, tint = p.text2)
            }
        }
    }
}

/** Страница раздела: заголовок и содержимое с прокруткой. */
@Composable
private fun SettingsPageFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun MicPicker(selected: MicChoice, onSelect: (MicChoice) -> Unit) {
    val choices = MicChoice.entries.filter { it.available }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) { Text(stringResource(choice.titleRes), maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize)) }
        }
    }
}

@Composable
private fun PacePicker(selected: SpeechPace, onSelect: (SpeechPace) -> Unit) {
    val choices = SpeechPace.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) { Text(stringResource(choice.title), maxLines = 1) }
        }
    }
}

/** Роль ИИ: провайдер — сегментами из тех, у кого есть ключ (модель внутри провайдера закреплена). */
@Composable
private fun RoleRow(title: String, providers: List<ProviderId>, selected: ProviderId, sameAsText: Boolean = false, onSelect: (ProviderId) -> Unit) {
    Text(title + if (sameAsText) " · " + stringResource(R.string.ai_role_same_as_text) else "", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        providers.forEachIndexed { index, provider ->
            SegmentedButton(
                selected = provider == selected,
                onClick = { if (provider != selected) onSelect(provider) },
                shape = SegmentedButtonDefaults.itemShape(index, providers.size),
                icon = {},
            ) { Text(provider.assistantName, maxLines = 1) }
        }
    }
}

/**
 * «Расходы по API» по моделям: за этот месяц и за всё время журнала. Оценка — токены из ответов API × цены
 * ([ru.vasev.glasses.llm.ModelPrices]); точный баланс — в кабинете провайдера.
 */
@Composable
private fun CostsSection(viewModel: SettingsViewModel) {
    val entries by viewModel.journal.collectAsStateWithLifecycle()
    val month = java.time.YearMonth.now()
    val zone = java.time.ZoneId.systemDefault()
    val byModel = entries.groupBy { it.model }.map { (model, list) ->
        val thisMonth = list.filter { java.time.YearMonth.from(it.time.atZone(zone)) == month }
        CostRow(model, thisMonth.sumOf { it.costUsd ?: 0.0 }, list.sumOf { it.costUsd ?: 0.0 }, thisMonth.size, list.size)
    }.sortedByDescending { it.total }
    if (byModel.isEmpty()) {
        Hint(stringResource(R.string.costs_empty))
    } else {
        byModel.forEach { row ->
            Section(ModelCatalog.byId(row.model)?.title ?: row.model) {
                Text(stringResource(R.string.costs_month, usd(row.month), row.monthCount), style = MaterialTheme.typography.bodyLarge)
                Hint(stringResource(R.string.costs_total, usd(row.total), row.count))
            }
        }
        val all = byModel.sumOf { it.month }
        Text(stringResource(R.string.costs_month_all, usd(all)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    Hint(stringResource(R.string.costs_hint))
}

private data class CostRow(val model: String, val month: Double, val total: Double, val monthCount: Int, val count: Int)

private fun usd(value: Double): String = if (value < 0.01 && value > 0) "< $0.01" else "$" + String.format(java.util.Locale.US, "%.2f", value)

private const val DEV_TAPS = 7
/** Раздел настроек — стеклянная карточка с заголовком. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        content()
    }
}

/** Заголовок прямо на фоне (над карточками ролей и расширенными настройками). */
@Composable
private fun BackgroundTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun BackgroundHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = glassBackgroundText(), modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun KeySection(provider: ProviderId, state: KeyState, viewModel: SettingsViewModel) {
    val key = state.key
    if (key != null && !state.editing) {
        Text(stringResource(R.string.key_saved, key.tail), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Hint(stringResource(R.string.key_encrypted, if (key.strongBox) stringResource(R.string.key_strongbox_suffix) else ""))
        if (state.confirmDelete) {
            Status(stringResource(R.string.key_delete_confirm, provider.title), isError = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(stringResource(R.string.delete), onClick = { viewModel.delete(provider) }, icon = R.drawable.ic_sym_delete, kind = GlassButtonKind.ERROR)
                GlassButton(stringResource(R.string.cancel), onClick = { viewModel.cancelDelete(provider) })
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(stringResource(R.string.key_replace), onClick = { viewModel.startReplace(provider) }, icon = R.drawable.ic_sym_key, kind = GlassButtonKind.PRIMARY)
                GlassButton(stringResource(R.string.key_delete), onClick = { viewModel.askDelete(provider) }, icon = R.drawable.ic_sym_delete)
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

/** «Моя валюта»: выпадающий список с названием валюты на языке экрана. */
@Composable
private fun CurrencyPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    Box {
        GlassButton(CurrencySettings.title(selected, locale) + " ▾", onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
            CurrencySettings.CHOICES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(CurrencySettings.title(code, locale), fontWeight = if (code == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        expanded = false
                        onSelect(code)
                    },
                )
            }
        }
    }
}

private val RATE_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** [markNoSearch] — пометить модели без веб-поиска (важно для ассистента, переводу поиск не нужен). */
@Composable
private fun ModelPicker(role: String, selected: ModelSpec, markNoSearch: Boolean, onSelect: (ModelSpec) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val noSearch = stringResource(R.string.model_no_web_search)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(role, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            GlassButton("${selected.title} ▾", onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
            // Меню — отдельное окно, размыть под ним приложение нельзя: оно непрозрачное, как в Material 3.
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
                ModelCatalog.all.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                (if (model.vision) model.title else stringResource(R.string.model_no_photo, model.title)) +
                                    if (markNoSearch && !model.webSearch) " · $noSearch" else "",
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant))
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
        GlassButton(stringResource(R.string.save), onClick = submit, enabled = !busy && input.isNotBlank(), kind = GlassButtonKind.PRIMARY)
        if (canCancel) GlassButton(stringResource(R.string.cancel), onClick = onCancel, enabled = !busy)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

/** Пояснение внутри стеклянной карточки: второстепенный цвет, подтемнённый до контраста 4.5:1. */
@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = glassAccent(accent = MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
private fun Status(text: String, isError: Boolean) {
    val c = MaterialTheme.colorScheme
    GlassCard(
        Modifier.fillMaxWidth(),
        tint = if (isError) c.errorContainer else c.secondaryContainer,
        text = if (isError) c.onErrorContainer else c.onSecondaryContainer,
        contentPadding = PaddingValues(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Язык адреса для таксиста: «Авто» (по стране цели) или язык из списка — названия на языке экрана. */
@Composable
private fun TaxiLanguagePicker(selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val auto = stringResource(R.string.settings_taxi_language_auto)
    fun title(tag: String) = java.util.Locale.forLanguageTag(tag).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    val options = remember(locale) { CountryLanguage.all.sortedBy(::title) }
    Box {
        GlassButton((selected?.let(::title) ?: auto) + " ▾", onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(20.dp)) {
            DropdownMenuItem(
                text = { Text(auto, fontWeight = if (selected == null) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            options.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(title(tag), fontWeight = if (tag == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        expanded = false
                        onSelect(tag)
                    },
                )
            }
        }
    }
}

/** Навигатор: Авто / Яндекс / Google. */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val choices = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (choice) {
                            ThemeMode.DARK -> R.string.theme_dark
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.SYSTEM -> R.string.theme_system
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

@Composable
private fun FontScalePicker(selected: Int, onSelect: (Int) -> Unit) {
    val choices = AppearanceSettings.FONT_SCALES
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) {
                Text(
                    "$choice",
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

@Composable
private fun TapPicker(selected: Tap, onSelect: (Tap) -> Unit) {
    // Двойное — первым: оно по умолчанию (одиночное срабатывает, когда поправляешь очки).
    val choices = listOf(Tap.DOUBLE, Tap.SINGLE)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) {
                Text(
                    stringResource(if (choice == Tap.DOUBLE) R.string.gesture_tap_double else R.string.gesture_tap_single),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

@Composable
private fun NavigatorPicker(selected: NavigatorChoice, onSelect: (NavigatorChoice) -> Unit) {
    val choices = NavigatorChoice.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { if (choice != selected) onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (choice) {
                            NavigatorChoice.AUTO -> R.string.navigator_auto
                            NavigatorChoice.YANDEX -> R.string.maps_yandex_short
                            NavigatorChoice.GOOGLE -> R.string.maps_google_short
                        },
                    ),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
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
                    softWrap = false,
                    // «Системный» при крупном шрифте не влезает в треть строки — уменьшается, а не обрезается.
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}
