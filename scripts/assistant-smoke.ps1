# Прогон сценариев ассистента на телефоне (debug-сборка, ключ Anthropic введён в приложении) — до и после переделок.
# Каждый сценарий — debug-команда + проверка по logcat. Свой чат на прогон; после — чат, тестовая заметка и снимки удаляются.
#   .\scripts\assistant-smoke.ps1 [-Package ru.vasev.glasses] [-Out result.json]
# Запросы к модели настоящие (~8 шт., центы). Ответы звучат в очки или в динамик.
param([string]$Package = 'ru.vasev.glasses', [string]$Out = '')
# Continue: в PowerShell 5 предупреждения adb в stderr при Stop становятся ошибками.
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$activity = "$Package/ru.vasev.glasses.MainActivity"

function Send([string]$extras) { & $adb shell "am start -n $activity $extras" 2>$null | Out-Null }
function Log { & $adb logcat -d -s LLM:D Glasses:D Notes:D Audio:D Tools:D 2>$null }
function Files([string]$dir) { (& $adb shell "run-as $Package ls $dir 2>/dev/null") -split "`r?`n" | Where-Object { $_ } }

# Ждёт, пока в логе появится строка по шаблону (или таймаут); возвращает подходящие строки.
function WaitFor([string]$pattern, [int]$timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        $hit = Log | Select-String -Pattern $pattern
        if ($hit) { return $hit }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    return $null
}

$results = [ordered]@{}
function Check([string]$name, [bool]$ok, [string]$detail) {
    $results[$name] = [ordered]@{ ok = $ok; detail = $detail }
    '{0,-28} {1}  {2}' -f $name, ($(if ($ok) { 'PASS' } else { 'FAIL' })), $detail
}
function LlmLine($hits) { ($hits | Select-Object -Last 1).Line -replace '^.*?role=', 'role=' }

# Исходные настройки, которые сценарии трогают: вернуть в конце.
$prefsImage = (& $adb shell "run-as $Package cat shared_prefs/image_source.xml 2>/dev/null") -join ''
$sourceWas = switch -regex ($prefsImage) { 'PHONE_CAMERA' { 'phone' } 'SIMULATOR' { 'simulator' } default { 'glasses' } }
$prefsGesture = (& $adb shell "run-as $Package cat shared_prefs/gestures.xml 2>/dev/null") -join ''
$gestureWas = if ($prefsGesture -notmatch 'name="enabled" value="true"') { 'off' } elseif ($prefsGesture -match 'SINGLE') { 'single' } else { 'double' }
$shotsBefore = Files 'files/shots'
$mediaBefore = Files 'files/media/chat'

& $adb shell am force-stop $Package
Send '--es tab assistant'; Start-Sleep 3
Send '--ez new_chat true'; Start-Sleep 1
& $adb logcat -c

# 1. Вопрос текстом → ответ модели.
Send "--es question 'Сколько будет 17 умножить на 3? Ответь одним числом.'"
$h = WaitFor 'role=assistant .*status=' 60
Check 'question' ([bool]($h -and (LlmLine $h) -match 'status=ok')) (LlmLine $h)
WaitFor 'end_of_speech' 30 | Out-Null; Start-Sleep 2; & $adb logcat -c

# 2. «Запомни» → заметка сохранена инструментом.
Send "--es question 'Запомни: тестовая заметка прогона, ключ от номера пятьсот двенадцать.'"
$h = WaitFor 'tool=save_note|note saved' 60
Check 'remember' ([bool]$h) ($(if ($h) { ($h | Select-Object -Last 1).Line.Trim() } else { 'нет записи о сохранении' }))
WaitFor 'role=assistant .*status=' 60 | Out-Null; Start-Sleep 4; & $adb logcat -c

# 3. Удаление заметки (защита: только со словом «удали» в моей фразе).
Send "--es question 'Удали заметку про ключ от номера пятьсот двенадцать.'"
$h = WaitFor 'tool=delete_notes' 60
Check 'delete-note' ([bool]$h) ($(if ($h) { ($h | Select-Object -Last 1).Line.Trim() } else { 'заметка не удалена' }))
WaitFor 'role=assistant .*status=' 60 | Out-Null; Start-Sleep 4; & $adb logcat -c

# 4. «Что я вижу» — снимок из симулятора очков уходит модели.
Send '--es image_source simulator'; Start-Sleep 4
Send "--es question 'Что на снимке? Одним предложением.' --ez with_frame true"
$h = WaitFor 'role=assistant .*status=' 90
Check 'what-i-see' ([bool]($h -and (LlmLine $h) -match 'status=ok' -and (LlmLine $h) -match 'photo=sent')) (LlmLine $h)
Start-Sleep 4; & $adb logcat -c
Send "--es image_source $sourceWas"; Start-Sleep 2

# 5. Веб-поиск (только Claude).
Send "--es question 'Найди в интернете: какая сегодня погода в Бангкоке? Коротко.'"
$h = WaitFor 'role=assistant .*status=' 90
Check 'web-search' ([bool]($h -and (LlmLine $h) -match 'status=ok' -and (LlmLine $h) -match 'web=[1-9]')) (LlmLine $h)
Start-Sleep 4; & $adb logcat -c

# 6. Перебить медиакнопкой во время ответа.
Send "--es question 'Расскажи подробно, в десяти предложениях, историю города Бангкок.'"
$first = WaitFor 'first_text=|end_of_speech' 60
Start-Sleep 3
& $adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
$h = WaitFor 'Assistant: interrupted|answer interrupted' 15
Check 'interrupt' ([bool]$h) ($(if ($h) { ($h | Select-Object -Last 1).Line.Trim() } else { 'перебивания нет' }))
# После перебивания ассистент слушает новый вопрос — дождаться, пока слушание кончится (тишина).
Start-Sleep 10; & $adb logcat -c

# 7. Вопрос жестом: двойное касание (MEDIA_NEXT) → сигнал «слушаю» → тишина 5 с → «отбой» без запроса.
Send '--es gesture double'; Start-Sleep 4
& $adb shell input keyevent KEYCODE_MEDIA_NEXT
$h = WaitFor 'gesture: .*action=ASK' 10
# Тишина — «отбой» через 5 с; в шумной комнате распознаватель может услышать фразу — тогда уходит вопрос. Оба исхода — жест сработал.
$after = WaitFor 'gesture question cancelled|role=assistant .*status=' 25
$what = if (-not $h) { 'жест не дошёл' } elseif (-not $after) { 'ask, но ни отбоя, ни вопроса за 25 с' } elseif (($after | Select-Object -Last 1).Line -match 'cancelled') { 'ask + отбой (тишина)' } else { 'ask + вопрос (что-то услышал)' }
Check 'gesture-ask' ([bool]($h -and $after)) $what
Send "--es gesture $gestureWas"; Start-Sleep 2

# Уборка: чат прогона и новые снимки.
Send '--ez delete_current_chat true'; Start-Sleep 2
foreach ($f in (Files 'files/shots') | Where-Object { $shotsBefore -notcontains $_ }) { & $adb shell "run-as $Package rm files/shots/$f" }
foreach ($f in (Files 'files/media/chat') | Where-Object { $mediaBefore -notcontains $_ }) { & $adb shell "run-as $Package rm files/media/chat/$f" }

$pass = ($results.Values | Where-Object { $_.ok }).Count
"итог: $pass из $($results.Count)"
if ($Out) { $results | ConvertTo-Json -Depth 3 | Set-Content -Encoding utf8 $Out }
