# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Проект

Android-приложение (Kotlin, Jetpack Compose, интерфейс на русском) для очков Ray-Ban Meta Gen 2 на базе Meta Wearables Device Access Toolkit (DAT SDK 1.0.1) и Claude API. Package `ru.vasev.glasses`, minSdk 29, сборка только под `arm64-v8a`. Очков пока нет: всё проверяется через MockDeviceKit (переключатель «Симулятор / настоящие очки» в приложении).

Две вкладки:
- **Ассистент** — голосовой вопрос → Claude (`claude-sonnet-5-5`) → ответ голосом; «Что я вижу» добавляет снимок с камеры очков. Память разговора и кэш промпта.
- **Перевод** — живой перевод разговора с собеседником (9 языков) через `claude-haiku-5-5`.

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

Логи: `adb logcat -s Glasses:D Translate:D`. `Glasses` — ассистент и расход токенов (`Claude usage: … cache_read=…`), `Translate` — задержки этапов перевода (`recognition=`, `translate=`, `tts_start=`, `end_of_speech→sound=`).

### Debug-запуск сценариев без голоса

Только в debug-сборке (`MainActivity.handleDebugIntent`). Текст передавать через `adb shell "…"` с одинарными кавычками, иначе adb режет его по пробелу:

```powershell
adb shell "am start -n ru.vasev.glasses/.MainActivity --es question 'Что написано на вывеске?' --ez with_frame true"
adb shell "am start -n ru.vasev.glasses/.MainActivity --es language TURKISH --es translate_partner 'Merhaba'"
adb shell "am start -n ru.vasev.glasses/.MainActivity --es translate_me 'Добрый день'"
```

Перед этим выдать разрешения: `adb shell pm grant ru.vasev.glasses android.permission.RECORD_AUDIO` (и `BLUETOOTH_CONNECT`).

## Секреты и конфигурация

Только в `local.properties` (в `.gitignore`), в код попадают через `BuildConfig` (`app/build.gradle.kts`):
- `anthropic_api_key` → `BuildConfig.ANTHROPIC_API_KEY`;
- `use_mock_device` → режим по умолчанию при первом запуске (дальше хранится в SharedPreferences);
- `mwdat_application_id`, `mwdat_client_token` — необязательны; в Developer Mode приложения Meta AI достаточно `0` (лог `Failed to read metadata … APPLICATION_ID` при этом ожидаем);
- `sdk.dir` писать с прямыми слешами (`C:/Users/...`) — экранированные обратные ломают AGP («Invalid file path»).

DAT SDK берётся из Maven Central, GitHub-токен не нужен. Ключ Anthropic, переданный через BuildConfig, извлекаем из APK — приложение только для личного использования.

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

**Claude** (`claude/`): `Anthropic` — общий клиент официального Java SDK и перевод ошибок SDK в русские сообщения. `ClaudeClient` (ассистент): adaptive thinking по умолчанию, effort `low`, автоматический кэш (`cacheControl` на уровне запроса), server-side fallback (`server-side-fallback-2026-07-01`, `fallbacks: "default"`). `Translator` — Haiku 5.5, `max_tokens` 300, без fallback (у Haiku его нет; отказ → `null` → «перевод недоступен»).

**Память ассистента** — `Conversation` (чистый Kotlin, покрыт тестами): окно обрезается пачкой (10 обменов → последние 5), чтобы реже сбивать кэш; картинкой отправляется только последний снимок, старые — пометкой «[фото]». **Ответы Claude хранятся только текстом, без thinking-блоков**: у Sonnet 5.5 thinking-блоки привязаны к неизменной истории, и после обрезки окна или замены фото их повторная отправка дала бы 400 на новых аккаунтах. Кэш срабатывает от 512 токенов — короткие текстовые разговоры не кэшируются, это нормально. Сброс: кнопка, голосом «новый разговор» / «забудь всё» (`Conversation.isResetCommand`), автосброс через 5 минут тишины.

**Перевод** (`translate/`): `TranslationPrompt` собирает user-сообщение (3 предыдущие реплики как контекст + направление + фраза), `NoiseFilter` не пускает в API пустые фразы и 1–2 междометия. В `TranslationViewModel.turn` микрофон снова включается только после окончания озвучки + 400 мс (защита от эха). Голос TTS для языка проверяется заранее (`VoiceStatus`); если не скачан — подсказка и кнопка, открывающая установку голосов текущего движка (`Speaker.enginePackage`).

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
