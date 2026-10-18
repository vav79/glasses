# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Проект

Android-приложение (Kotlin, Jetpack Compose, интерфейс на английском и русском) для очков Ray-Ban Meta Gen 2 на базе Meta Wearables Device Access Toolkit (DAT SDK 1.0.1) и ИИ-провайдеров (Claude API; DeepSeek как альтернатива и запасной вариант). Package `ru.vasev.glasses`, minSdk 29, сборка только под `arm64-v8a`. Очков пока нет: снимки для «Что я вижу» можно брать с камеры телефона или из галереи, а в debug-сборке — из симулятора очков (MockDeviceKit).

Три вкладки:
- **Ассистент** — голосовой вопрос → модель (по умолчанию `claude-sonnet-5-5`) → ответ голосом; «Что я вижу» добавляет снимок с камеры очков. Память разговора и кэш промпта.
- **Перевод** — живой перевод разговора с собеседником (9 языков), по умолчанию `claude-haiku-5-5`.
- **Настройки** — ключи провайдеров, модель для каждой роли, запасная модель.

## Команды

Окружение Windows. Gradle запускается на JBR из Android Studio, отдельного JDK нет:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug                 # сборка
.\gradlew.bat installDebug                  # сборка + установка на телефон по USB
.\gradlew.bat testDebugUnitTest             # unit-тесты (JVM, без устройства)
.\gradlew.bat testDebugUnitTest --tests "ru.vasev.glasses.translate.NoiseFilterTest"   # один класс
```

`adb` не в PATH: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`. Тестовый телефон — Galaxy S25 Ultra. Если `adb devices` пуст, а Windows видит только «USB Composite Device» без «ADB Interface», на телефоне выключилась отладка по USB (Samsung сбрасывает её; ещё мешает «Автоблокировщик»).

Логи: `adb logcat -s LLM:D Translate:D Audio:D Glasses:D`. `LLM` — строка на каждую попытку запроса к ИИ для сравнения моделей (`role= provider= model= status= latency= [first_text=] in= out= cache_read= cache_write= reasoning= photo=sent|placeholder|none fallback=no|yes(причина)`), `Translate` — задержки этапов перевода (`recognition=`, `translate=`, `tts_start=`, `end_of_speech→sound=`), `Audio` — маршрут звука, `Glasses` — ошибки ассистента и `Assistant: end_of_speech→sound=` (конец фразы → первый звук ответа).

### Debug-запуск сценариев без голоса

Только в debug-сборке (`MainActivity.handleDebugIntent`). Текст передавать через `adb shell "…"` с одинарными кавычками, иначе adb режет его по пробелу:

```powershell
adb shell "am start -n ru.vasev.glasses/.MainActivity --es question 'Что написано на вывеске?' --ez with_frame true"
adb shell "am start -n ru.vasev.glasses/.MainActivity --es language TURKISH --es translate_partner 'Merhaba'"
adb shell "am start -n ru.vasev.glasses/.MainActivity --es translate_me 'Добрый день'"
# модель для роли (сохраняется в настройках) и имитация сбоя Claude на следующий запрос:
adb shell "am start -n ru.vasev.glasses/.MainActivity --es model_assistant deepseek-v4-pro --es model_translation deepseek-flash"
adb shell "am start -n ru.vasev.glasses/.MainActivity --es fail_claude server --es question 'Привет'"   # server|network|forbidden|ratelimit
# источник снимков (камера телефона запросит разрешение, если его нет):
adb shell "am start -n ru.vasev.glasses/.MainActivity --es image_source simulator"   # glasses|phone|gallery|simulator
```

«Нет интернета» проверяется авиарежимом: `adb shell cmd connectivity airplane-mode enable|disable` (USB-отладка при этом работает). После проверок верните модели по умолчанию — debug-параметры их сохраняют.

Перед этим выдать разрешения: `adb shell pm grant ru.vasev.glasses android.permission.RECORD_AUDIO` (и `BLUETOOTH_CONNECT`).

## Секреты и конфигурация

**API-ключи провайдеров в сборку не входят.** Они вводятся на вкладке «Настройки» и хранятся на телефоне (`security/ApiKeyStore`, слот на `ProviderId`): AES-256-GCM одним ключом из Android Keystore (на S25 — StrongBox), в SharedPreferences `secure` лежит только `IV || шифротекст` на слот (`anthropic_key_ct`, `deepseek_key_ct`, …), в AAD — имя слота. Имена слота Anthropic и alias Keystore (`anthropic_api_key_v1`) остались от версии с одним ключом — не переименовывать, иначе сохранённый ключ перестанет расшифровываться. Расшифровка — в памяти: перед каждым запросом (провайдер подставляет ключ заголовком только в этот запрос: `x-api-key` у Anthropic, где клиент SDK собран с заглушкой, `Authorization: Bearer` у OpenAI-совместимых) и на экране «Настройки» ради последних 4 символов. Признак «ключ есть» — по наличию шифротекста, без расшифровки. EncryptedSharedPreferences не используем (устарела). Резервное копирование и перенос данных на другое устройство отключены (`allowBackup=false` + `res/xml/data_extraction_rules.xml`). Проверка, что ключа нет в APK: распаковать APK и искать `sk-ant` по всем файлам (в самом .apk строки сжаты). Ключи DeepSeek начинаются с `sk-`: в logcat искать шаблон `sk-[A-Za-z0-9_-]{16,}` — голое `sk-` даёт ложные совпадения (`task-display-area`). Не вводите ключ через `adb shell input text` — adbd пишет текст команды в logcat. Debug-запуск сценариев работает только после ввода ключа в приложении.

Остальное — в `local.properties` (в `.gitignore`), в код попадает через `BuildConfig` (`app/build.gradle.kts`):
- `mwdat_application_id`, `mwdat_client_token` — необязательны; в Developer Mode приложения Meta AI достаточно `0` (лог `Failed to read metadata … APPLICATION_ID` при этом ожидаем);
- `sdk.dir` писать с прямыми слешами (`C:/Users/...`) — экранированные обратные ломают AGP («Invalid file path»).

DAT SDK берётся из Maven Central, GitHub-токен не нужен.

## Архитектура

**`GlassesApp`** держит общие на всё приложение объекты: `GlassesManager`, `SpeechSource`, `AudioOutput` (+ `Speaker`). Обе вкладки пользуются одним распознавателем и одним TTS, чтобы не спорить за звук; при переключении вкладок `MainActivity.selectTab` останавливает другую.

**Звук — через сменные интерфейсы** (`speech/`), это точка будущего перехода на очки:
- `SpeechSource.listen(languageTag)` → `Heard(text, endOfSpeechAt)`. Реализация `PhoneMicSpeechSource` — системный `SpeechRecognizer`, **один экземпляр на всё время работы**: пересоздание на каждую фразу даёт `ERROR_SERVER_DISCONNECTED` (код 11), который обрывает непрерывный режим. `SpeechException.noSpeech` / `.transient` — тишина и временные сбои, их непрерывный режим проглатывает.
- `AudioOutput.speak(text, locale, channel)` возвращается после `onDone` TTS. Каналы `ME` («мне») и `PARTNER` (собеседнику).

**Bluetooth-гарнитура** (репетиция перед очками — они подключаются так же, по HFP + A2DP) — `AudioRouter`, `RoutedSpeechSource`, `PhoneSpeakerOutput`; логи под тегом `Audio`:
- `Talker.USER` (ассистент, «Я отвечаю») слушается микрофоном гарнитуры: `setCommunicationDevice(SCO)` + `MODE_IN_COMMUNICATION`, распознаватель Google при этом пишет с гарнитуры. `Talker.PARTNER` — всегда микрофон телефона.
- Канал `ME` — обычный медиа-маршрут (A2DP в гарнитуру). Канал `PARTNER` при подключённой гарнитуре — синтез в файл и `MediaPlayer.setPreferredDevice(встроенный динамик)` с `USAGE_MEDIA`: TTS не умеет выбирать устройство.
- **Ловушка:** после `clearCommunicationDevice()` SCO разрывается асинхронно (на Galaxy S25 + Buds Core ~1,5 с). Пока он жив, телефон «в звонке», и звук уходит в гарнитуру с громкостью звонка, хотя `routedDevice` рапортует динамик. Поэтому `releaseMic()` запускает ожидание (событие разрыва SCO + смена устройства связи, таймаут 3 с), а вывод перед воспроизведением вызывает `awaitRouteSettled()`; ожидание перекрывается временем перевода.
- Гарнитура отключилась или не включила микрофон за 4 с — молча работаем через телефон.

**ИИ-провайдеры** (`llm/`). `AssistantClient` (`claude/`) и `Translator` (`translate/`) задают только промпт, `max_tokens` (2048 / 300) и `Effort` (оба `NONE` — без размышлений), а всё остальное делает `LlmService`:
- Модель по роли — из `AiSettings` (по умолчанию Sonnet 5.5 / Haiku 5.5). Каталог — `ModelCatalog`: `claude-sonnet-5-5`, `claude-haiku-5-5`, `deepseek-flash` (DeepSeek-V4.1-Flash, видит фото), `deepseek-v4-pro` (только текст, картинка → 400). Старые `deepseek-v4-flash*` у DeepSeek выведены из работы.
- `ChatProvider` → `AnthropicProvider` (официальный Java SDK: effort `low`; `Effort.NONE` выключает размышления — у Sonnet 5.5 `thinking: {type: "between_tools"}` (`disabled` там даёт 400), у Haiku 5.5 `{type: "disabled"}`, оба только при effort не выше `high`; авто-кэш только для ассистента; server-side fallback `server-side-fallback-2026-07-01` только у Sonnet) и `OpenAiCompatibleProvider` (OkHttp + org.json, настраивается `ProviderConfig`: URL, слот ключа, `Dialect`). У DeepSeek thinking включён по умолчанию: `reasoning_effort` `none` и для перевода, и для ассистента; фото — data-URL с `detail: high` (при `low` картинка ужимается до 512×512 и мелкий текст не читается). OpenAI: `ProviderConfigs.OPENAI` и диалект готовы — добавить модели в каталог.
- Модели без фото получают снимки пометкой «[фото]». «Что я вижу» на модели без фото автоматически уходит в модель того же провайдера с фото (`ModelCatalog.visionFor`, метка «· для фото»).
- Запасная модель (`FallbackPolicy`, чистая логика под тестами): только если сбой у Claude — 403, 429, 5xx (в т. ч. 529) или обрыв связи — один повтор через DeepSeek Flash с таймаутом 20 с; не при 401/400 и не без ключа DeepSeek. Сети нет совсем (`ConnectivityManager`) — сразу «Нет интернета», ни одной попытки. Фото в запасную модель — только с разрешением в настройках (по умолчанию выключено): иначе «Что я вижу» отвечает «Claude недоступен, фото не отправлено», а снимки из истории уходят пометкой. В ассистенте перед ответом звучит «Отвечает запасная модель» (в потоке — только если сбой до первого слова), в переводе — только метка. Сообщения `LlmException` («нет интернета» и т. п.) ещё и озвучиваются в канал ME — с очками на экран не смотрят.
- У каждого ответа — метка модели (`Answer.tag`, подпись собирает `modelLabel`): в чате и в ленте перевода. Без ключа для выбранной модели кнопки неактивны, вместо ошибки — `MissingKeyNotice`.
- Отказ модели: у Claude `stop_reason: refusal`, у DeepSeek `finish_reason: content_filter` → `Answer.text = null` → «не могу ответить» / «перевод недоступен».
- Имитация сбоев — `DebugFaults` (только debug).

**Потоковые ответы ассистента** (`answer/`, `ChatProvider.stream`). Ассистент получает ответ потоком: у Claude — `createStreaming` (только текстовые дельты, thinking пропускается), у DeepSeek — SSE (`stream_options.include_usage`, `reasoning_content` пропускается; разбор строк — `OpenAiCompatibleFormat.parseStreamLine`). Перевод остался на `complete`.
- Ответ двухуровневый: суть в 1–2 предложениях, строка-маркер `⟦more⟧` (`SpokenAnswer.MARKER`, его же называет системный промпт), подробности. `SpokenAnswer` отдаёт в озвучку только предложения сути (маркер может прийти частями; без маркера — не больше 3 предложений), подробности — в чат под «Подробнее»; маркер не показывается и в память не попадает (в памяти — суть + подробности).
- `SentenceSplitter` режет поток на предложения: не режет на числах (`3.5`, `$4.99`), сокращениях из `res/raw/abbreviations.txt` (`*` в конце строки — сокращение может завершать предложение: «и т. д.»), инициалах и строчной букве после знака. Предложения идут в `Channel`, отдельная корутина проговаривает их подряд, пока модель пишет дальше.
- Запасная модель — только если сбой до первого слова (`LlmService.stream`, событие `onFallback` → «Отвечает запасная модель» первой фразой). Обрыв посреди ответа: сказанное остаётся, звучит ошибка, без повтора. Сторожевой таймер: 20 с без единого события потока (`onEvent`) — ошибка сети, а не ожидание таймаута чтения.
- **Перебить** — кнопка микрофона во время «думаю/говорю» (`Phase.interruptible`) или кнопка гарнитуры: запрос и речь останавливаются, сразу слушается новый вопрос. Касание остального экрана ответ не прерывает. Кнопка гарнитуры — `HeadsetButtons`: медиасессия создаётся на время ответа и уничтожается после (иначе она перехватывала бы кнопки у музыки); пока ответ идёт, в процессе крутится беззвучный `AudioTrack` — Android отдаёт кнопки приложению, которое само играет звук, а TTS играет в процессе движка синтеза. Проверка без гарнитуры: `adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE` (или `KEYCODE_HEADSETHOOK`).
- **Голосовое «стоп»** (настройка «Гарнитура», по умолчанию выключено, только с гарнитурой с микрофоном): `AudioRouter.holdMic()` держит SCO на весь ответ (включается параллельно с запросом к модели), распознаватель по кругу ждёт `R.array.stop_commands`; ответ при этом звучит в качестве звонка — у классического Bluetooth микрофон и A2DP одновременно не работают. «Стоп» только останавливает, без нового вопроса.
- Пока звучит речь (ассистент и перевод), музыка других приложений приглушается: `AudioFocus` — `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, захваты считаются; ответ ассистента держит фокус целиком (`AudioOutput.holdFocus`), чтобы музыка не всплывала между предложениями.
- Замер: лог `Glasses` `Assistant: end_of_speech→sound=`; в строке `LLM` потока — `first_text=` (время до первого слова модели). Замер на 5 вопросах × 2 (медиана, мс): Sonnet 3551 (до стриминга) → 3479 (стриминг) → 2830 (стриминг без размышлений); DeepSeek Flash 2586 → 2515 → 2132. Основное время — до первого слова модели (`first_text`: Sonnet ≈ 2,0 с, DeepSeek ≈ 1,3 с), ещё ≈ 0,8 с — дописать первое предложение и запустить TTS.

**Память ассистента** — `Conversation` (чистый Kotlin, покрыт тестами): окно обрезается пачкой (10 обменов → последние 5), чтобы реже сбивать кэш; картинкой отправляется только последний снимок, старые — пометкой «[фото]». **Ответы Claude хранятся только текстом, без thinking-блоков**: у Sonnet 5.5 thinking-блоки привязаны к неизменной истории, и после обрезки окна или замены фото их повторная отправка дала бы 400 на новых аккаунтах. Кэш срабатывает от 512 токенов — короткие текстовые разговоры не кэшируются, это нормально. Сброс: кнопка, голосом «новый разговор» / «забудь всё» (`Conversation.isResetCommand`), автосброс через 5 минут тишины.

**Перевод** (`translate/`): `TranslationPrompt` собирает user-сообщение (3 предыдущие реплики как контекст + направление + фраза), `NoiseFilter` не пускает в API пустые фразы и 1–2 междометия. В `TranslationViewModel.turn` микрофон снова включается только после окончания озвучки + 400 мс (защита от эха). Голос TTS для языка проверяется заранее (`VoiceStatus`); если не скачан — подсказка и кнопка, открывающая установку голосов текущего движка (`Speaker.enginePackage`).

**Источник снимков** (`camera/`) — `ImageSource`, выбирается в карточке вверху «Ассистента» (`ImageSourceSettings`, по умолчанию «Очки»):
- «Очки» — DAT SDK. Не подключены — «Что я вижу» не блокируется, а говорит «Очки не подключены…» и подсказывает камеру телефона; автоматически не переключает (телефон может лежать в кармане).
- «Камера телефона» — CameraX (`PhoneCamera`), задняя камера: видоискатель в карточке, `ImageCapture` по «Что я вижу». Камера привязана к жизненному циклу видоискателя — включена только пока он на экране. Разрешение `CAMERA` запрашивается при выборе источника; если его отозвали в настройках Android — вместо чёрного видоискателя «Нет доступа к камере» и «Разрешить» (`MainActivity.cameraGranted` перепроверяется в `onResume`).
- «Из галереи» — системный `PickVisualMedia`: приложение получает только выбранный снимок, разрешений нет.
- «Симулятор очков» — только в debug-сборке; он же включает MockDeviceKit (`GlassesManager.setMockMode`, в release всегда выключен).
- Все снимки проходят `ImagePrep`: поворот (с камеры — `rotationDegrees`, из галереи — EXIF через `ImageDecoder`), длинная сторона ≤ 1024 px и перекодирование `Bitmap.compress` — EXIF (в т. ч. GPS) не уходит. Debug-лог `Image`: `exif_в_исходнике=… exif_в_отправляемом=false`. У снимка в чате — метка источника.

**Оформление** (`ui/`):
- Тема — `GlassesTheme(dark)`: dynamic color на Android 12+, светлая/тёмная по системе; запасная палитра M3 на бирюзовом `#00696B` (цвет иконки). Заставка — `core-splashscreen` (`Theme.Glasses.Starting` → `postSplashScreenTheme`), `installSplashScreen()` до `super.onCreate`.
- Иконка приложения — адаптивная (`mipmap-anydpi-v26/ic_launcher.xml`): облачко речи с очками + монохромный слой для тематических иконок; рисунок внутри безопасной зоны (круг R=33 из 108).
- Иконки — Material Symbols Outlined как векторные XML (`res/drawable/ic_sym_*.xml`, скачаны из `google/material-design-icons`, атрибут `android:tint` удалён — цвет задаёт `Icon`). `material-icons-extended` не используем. Невыбранные варианты лежат в `src/debug/res` — только для витрины.
- Витрина вариантов иконок — только debug: `adb shell am start -n ru.vasev.glasses/.debug.ShowcaseActivity`; выбор пишется в лог `Showcase` и в `shared_prefs/showcase.xml`.
- `MicButton` — стеклянная капля (см. «Стекло»): покой / «слушаю» (свечение по громкости — `SpeechSource.listen(onLevel)` из RMS распознавателя) / «думаю» / «говорю»; во время ответа нажатие перебивает, пока снимаем или слушаем — «стоп». `Haptics` — щелчок в начале и двойной в конце прослушивания (только ассистент).
- Чат: пузыри, метки модели и источника снимка — под пузырём; снимок и перевод открываются на весь экран (`FullscreenPhoto`, `FullscreenText`).
- Доступность: у иконок без текста — `contentDescription`, у иконок рядом с текстом — `null`; строка «подпись + Switch» — один `toggleable(role = Switch)`. Проверка: `uiautomator dump` — у каждого кликабельного узла (с потомками) есть подпись и он ≥ 48 dp (на S25 это 135 px).
- Скриншоты в светлой теме: `adb shell cmd uimode night no` — после проверки вернуть исходное значение (`cmd uimode night` показывает текущее).

**Стекло** (`ui/glass/`, «лёгкое стекло», выбрано на витрине). Haze 2.0.1 (`haze`, `haze-blur`, `haze-glass`, `haze-blur-materials`).
- `GlassRoot` — фон и окружение стекла: `GlassBackground` (градиент из цветов обоев, 3 пятна по фигурам Лиссажу, рисуется в фазе отрисовки) — источник размытия `GlassLayer.BACKGROUND`; цвет текста по умолчанию — onSurface (непрозрачного `Surface` больше нет — без этого текст был бы чёрным в тёмной теме). Фон стоит на вкладке «Перевод», в энергосбережении (подписка на `ACTION_POWER_SAVE_MODE_CHANGED`) и при «Убрать анимацию» (`ValueAnimator.areAnimatorsEnabled`).
- `Modifier.glass(shape, tint, text, blur)`: подложка + блик по краю + тонкая обводка + тень **только снаружи** (`outerShadow`: размытый контур с вырезанной формой, кэшируется в ALPHA_8-картинку на размер — системная тень и `dropShadow` просвечивают сквозь стекло, а `BlurMaskFilter` в каждом кадре нагружал видеокарту). `blur = true` — только у панелей поверх прокручиваемого содержимого (вкладки, кнопки внизу, капля): размывают `GlassLayer.CONTENT` (`glassContentSource()` на списке) и фон. Карточки и пузыри на фоне без размытия — под ними гладкий градиент, а каждый размытый элемент перерисовывается при прокрутке.
- **Контраст ≥ 4.5:1 — рассчитывается** (`Contrast`, чистый Kotlin под тестами): плотность подложки — наименьшая, при которой текст держит 4.5:1 против всего, что может оказаться позади (цвета фона; под панелями ещё пузыри и размытый текст), но не ниже 0.14 (цветное стекло — 0.45, иначе на тёмном фоне собеседник и я не различаются). Акцент (`glassAccent`) и второстепенный текст на фоне (`glassBackgroundText`) подтемняются к onSurface ровно до 4.5:1 — у палитры обоев Samsung `primary` на светлом стекле давал 3.7:1. Подпись выбранной вкладки — всегда onSurface (под панелью проезжает содержимое любой яркости), выбор — пилюлей, жирным и акцентной иконкой. Проверка по пикселям: скрипт сравнивает 3-й и 97-й перцентили яркости в рамке каждого текста из `uiautomator dump`.
- «Меньше прозрачности» (простые настройки, `ProfileSettings.lessTransparency`) — подложка +0.3, размытие ×1.5; включается и само при системных «Текст высокой контрастности» / повышенном контрасте (Android 14+). Android 11 и ниже — без размытия, подложка плотнее (+0.25).
- Компоненты: `GlassButton` (пилюля, виды PRIMARY / NORMAL / TONAL_TERTIARY / ERROR), `GlassIconButton`, `GlassCard`; `MicButton` — стеклянная капля: свечение внутри по состоянию (слушаю — по громкости, думаю — бегущий блик, говорю — дыхание). Выпадающие меню — отдельные окна, их не размыть: оставлены непрозрачными M3. Полноэкранный перевод — непрозрачный (читают издалека и на солнце).
- Витрина вариантов (лёгкое / матовое / жидкое на Backdrop / гибрид) — только debug: `adb shell am start -n ru.vasev.glasses/.debug.GlassShowcaseActivity`; Backdrop (`io.github.kyant0:backdrop`) подключён только к debug.
- Плавность (S25 Ultra, 120 Гц — проверено по `FrameInterval` 8,3 мс в `dumpsys gfxinfo framestats`): прокрутка чата — 0 кадров из 120 не уложились в `FrameDeadline`, «тормозящих» по всему замеру 0,07 %; кадр занимает 12–14 мс, но благодаря конвейеру отрисовки укладывается в свой срок. Прокрутка настроек и перевод — 0,21 % и 0,71 % «тормозящих» (до стекла 0,35–2,31 %).

**Язык** (`i18n/`). Все строки — в ресурсах: английский по умолчанию (`values/`), русский (`values-ru/`); в Kotlin-коде нет кириллицы, кроме комментариев (тесты — по-русски). Междометия для `NoiseFilter` — данные в `res/raw/noise_fillers.txt`. Язык приложения (Системный / Русский / English) — per-app language: `AppCompatDelegate.setApplicationLocales` + `res/xml/locales_config.xml`, на Android 10–12 сохраняется через `AppLocalesMetadataHolderService`; поэтому `MainActivity` — `AppCompatActivity`, а обе темы (`Theme.Glasses` и `postSplashScreenTheme`) — наследники AppCompat. Язык приложения = язык общения с ассистентом: распознавание, голос, ответ модели, голосовые команды (`R.array.reset_commands`) и «мой язык» в переводе (`PartnerLanguage.forApp`; в списке собеседника нет моего языка, русский есть). Код вне экрана берёт строки через `L.s(...)` (контекст с языком приложения), язык — из `LanguageSettings.current` на каждый вопрос/фразу, поэтому смена языка действует сразу: Activity пересоздаётся, ViewModel и история остаются, вкладка сохраняется в `onSaveInstanceState`. Промпты — на английском с названием языка (`englishName`); качество перевода на русский проверено против прежних русских промптов — без разницы. Логи — на английском. Проверка без кнопок: `adb shell cmd locale set-app-locales ru.vasev.glasses --locales en` (пусто — системный).

**Роли** (`profile/`). Первый запуск: язык → роль (`OnboardingScreen`, шаг переживает пересоздание Activity при смене языка). `UserRole` — набор модулей, а не замок: порядок вкладок (первая — стартовая) и видимость расширенных настроек. Путешественник — «Перевод» первым; исследователь — «Ассистент» первым (источник снимков не трогается); разработчик — плюс вкладка «Разработка», расширенные настройки видны сразу. Остальным расширенные (модели, запасная модель, журнал) — по кнопке «Показать расширенные». Роль меняется в настройках, ничего не теряется. Всё в SharedPreferences `profile`. Повторить первый запуск, не стирая данные (иначе пропадут ключи): `adb shell "am start -n ru.vasev.glasses/.MainActivity --ez reset_onboarding true"`.

**Журнал запросов** (`llm/RequestJournal`): запись на каждую попытку, как строка лога `LLM`; JSONL `files/requests.jsonl`, последние 1000. По умолчанию только метаданные, тексты вопроса и ответа — отдельным переключателем (выключен). Стоимость — оценка по `ModelPrices` (Claude — прайс Anthropic; DeepSeek — пиковые цены, вне пика (будни 01–04 и 06–10 UTC — пик) ×0,5 — скидка оценочная). Для ответов ассистента запись дописывается в конце хода: `first_sound_ms` (конец фразы → первый звук) и `interrupted` (перебит; счёт токенов тогда неполный — у Claude выход приходит только в конце потока). Экспорт — копия в `cache/export` через FileProvider (`${applicationId}.files`) и «Поделиться».

**Очки** (`glasses/`): `GlassesManager.captureJpeg` на каждый снимок открывает короткую DAT-сессию: `createSession` → ждать `STARTED` → `addCamera` → `stream.start()` → ждать `STREAMING` (критическая ошибка стрима ловится сразу) → `stream.capturePhoto()` → стоп. Стабильный `stream.capturePhoto()` выбран намеренно: отдельная съёмка `camera.photo` экспериментальная. `MockGlassesController` поднимает MockDeviceKit: **без `setCameraFeed` симулятор не запускает стрим**, поэтому `MockVideoEncoder` при первом включении кодирует `res/raw/mock_view.jpg` в 2-секундное HEVC-видео (ffmpeg не нужен) и кэширует его.

Подводные камни DAT: `DatResult.fold` не inline — `return` из лямбды нельзя, используйте `getOrNull()` / `errorOrNull()`. Лог `Wearables SDK already initialized` безвреден (MockDeviceKit инициализирует SDK повторно).

## Порядок работы

- Сначала план, код — после подтверждения пользователя.
- Каждая функция проверяется на телефоне (debug-запуск + logcat + скриншоты `adb shell screencap`), затем коммит от имени Alexandr Vasev (настроено в локальном git-конфиге репозитория). Что не удалось проверить на устройстве — явно сказать.
- Версии зависимостей — в `gradle/libs.versions.toml`; при обновлении берите релизы старше двух недель.

## Следующий этап: настоящие очки Ray-Ban Meta Gen 2 и запуск через «Hey Meta»

Всё ниже — план, ещё не реализовано. Speech, Voice Invocations, in-stream audio и отдельная съёмка фото в DAT 1.0.1 экспериментальные: для разработки и беты годятся, в продакшен-канал их опубликовать нельзя. API перед реализацией сверять через `search_dat_docs` (плагин `mwdat-android`).

1. **Подготовка.** Meta AI на телефоне + Developer Mode. Проект в Wearables Developer Center: одобрения Camera, Microphone/Speech, Voice Invocation; произносимое имя приложения для «Hey Meta». Проверить на реальном устройстве регистрацию (`Wearables.startRegistration`, `handleIntent` в `onCreate`/`onNewIntent` — уже есть) и DAT-разрешения `Permission.CAMERA` и `Permission.MICROPHONE` (запрос только по действию пользователя, контракт `Wearables.RequestPermissionContract` уже подключён для камеры).
2. **Камера.** Код снимка уже рассчитан на реальные очки; проверить ветку `PhotoData.HEIC`, замерить время «нажатие → кадр». Если открытие сессии на каждый снимок окажется долгим — держать сессию открытой, пока активна вкладка.
3. **Микрофон очков — новая реализация `SpeechSource`.** Сначала DAT Speech (артефакт `com.meta.wearable:mwdat-speech:1.0.1`; `session.addSpeech()` после `STARTED`, `transcriptions` с `isFinal`; в тестах — `glasses.services.speech.simulateTranscription`). Проверить, какие локали он поддерживает: русский и 9 языков перевода. Если языков не хватает — PCM из in-stream audio (`StreamConfiguration(audioCodec = AudioCodec.PCM(RATE_16000, 1))`, `stream.audioStream`; работает только вместе с видеопотоком) → `SpeechRecognizer` через `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 13+, поддержку на S25 проверить) или облачное потоковое распознавание (Google Cloud Speech-to-Text v2). Выбор источника — настройка в приложении; `PhoneMicSpeechSource` остаётся запасным.
4. **Звук в очки — новая реализация `AudioOutput`.** Канал `ME` → очки (Bluetooth), канал `PARTNER` должен принудительно идти в динамик телефона — иначе при подключённых очках TTS по умолчанию уйдёт в них. Вероятный путь: синтез в файл/буфер и воспроизведение через `AudioTrack` с `setPreferredDevice` (`TYPE_BUILTIN_SPEAKER` для собеседника, Bluetooth-устройство очков для меня). Защита от эха (`onDone` + пауза) сохраняется.
5. **«Hey Meta, start …».** Voice Invocations — в `mwdat-core`, сессия не нужна. В `GlassesApp` — один app-scoped `Wearables.startVoiceInvocationsStream(AutoDeviceSelector())`; на `LaunchApp` открыть ассистента и сразу начать слушать вопрос, ответить на каждое событие ровно один раз (`responseHandle.sendSuccess` / `sendFailure`). В `MainActivity` проверять `isVoiceInvocationsIntent(intent)` в `onCreate` и `onNewIntent` (не разбирать extras вручную). Тест без очков: `glasses.services.voiceInvocation.simulateLaunchAppAction()` после проверки `hasConnectedApps()`.
6. **Проверка.** Сначала каждый шаг в MockDeviceKit, затем на очках: задержки по logcat, эхо в переводе, расход батареи при открытой сессии и видеопотоке.
