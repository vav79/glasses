package ru.vasev.glasses.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Подключённая Bluetooth-гарнитура: [mic] — HFP/SCO (или LE Audio) для микрофона, [output] — для звука. */
data class Headset(val name: String, val mic: AudioDeviceInfo?, val output: AudioDeviceInfo?)

/**
 * Маршрутизация звука между телефоном и Bluetooth-гарнитурой (репетиция перед очками — они подключаются так же).
 *
 * - Микрофон гарнитуры включается через `setCommunicationDevice` (SCO): пока он включён, Android пишет
 *   звук распознавания с гарнитуры, а медиа-звук в неё не идёт — поэтому после фразы SCO сразу отключается.
 * - Звук «мне» идёт обычным медиа-маршрутом (при подключённой гарнитуре — в неё), «собеседнику» —
 *   принудительно в динамик телефона ([builtinSpeaker]).
 * - Если гарнитуры нет или она не ответила — молча работаем через телефон.
 */
class AudioRouter(context: Context) {

    private val appContext = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private var settled: CompletableDeferred<Unit>? = null
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = context.mainExecutor

    private val _headset = MutableStateFlow<Headset?>(null)
    val headset: StateFlow<Headset?> = _headset.asStateFlow()

    private var micRouted = false

    init {
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
            },
            main,
        )
        refresh()
    }

    /** Подготовить микрофон для того, кто сейчас говорит. Возвращает описание выбранного входа (для логов). */
    suspend fun routeMic(talker: Talker): String {
        val mic = _headset.value?.mic
        if (talker != Talker.USER || mic == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            releaseMic()
            return log("in", "микрофон телефона" + if (talker == Talker.PARTNER) " (говорит собеседник)" else "")
        }
        return if (switchToHeadsetMic(mic)) {
            log("in", "микрофон гарнитуры «${_headset.value?.name}» (${typeName(mic)})")
        } else {
            releaseMic()
            log("in", "микрофон телефона (гарнитура не включила микрофон за ${SCO_TIMEOUT_MS} мс)")
        }
    }

    /**
     * Отключить микрофон гарнитуры, чтобы вернуть медиа-звук и нормальный режим телефона.
     * Не блокирует: SCO-канал разрывается асинхронно (на Samsung — до 1–2 с), а пока он жив, телефон
     * остаётся «в звонке» и звук уходит в гарнитуру с громкостью звонка. Перед воспроизведением нужно
     * дождаться [awaitRouteSettled] — так ожидание идёт параллельно с переводом.
     */
    @Suppress("DEPRECATION") // ACTION_SCO_AUDIO_STATE_UPDATED: другого события о разрыве SCO в API нет
    fun releaseMic() {
        if (!micRouted) return
        micRouted = false
        val startedAt = SystemClock.elapsedRealtime()
        val done = CompletableDeferred<Unit>()
        settled = done

        var scoDisconnected = false
        var commLeftHeadset = false
        fun check(reason: String) {
            if (done.isCompleted || !(scoDisconnected && commLeftHeadset)) return
            Log.d(TAG, "микрофон гарнитуры отключён за ${SystemClock.elapsedRealtime() - startedAt} мс ($reason); ${state()}")
            done.complete(Unit)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isInitialStickyBroadcast) return
                val sco = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                if (sco == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    scoDisconnected = true
                    check("SCO разорван")
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (device?.type !in HEADSET_MIC_TYPES) {
                    commLeftHeadset = true
                    check("устройство связи: ${describe(device)}")
                }
            }.also { audio.addOnCommunicationDeviceChangedListener(mainExecutor, it) }
        } else {
            null
        }
        done.invokeOnCompletion {
            main.post {
                runCatching { appContext.unregisterReceiver(receiver) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener != null) {
                    audio.removeOnCommunicationDeviceChangedListener(listener)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL

        // LE Audio не шлёт события SCO; если устройство связи уже сменилось — ждать нечего.
        if (_headset.value?.mic?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || !audio.isBluetoothScoOnCompat()) {
            scoDisconnected = true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audio.communicationDevice?.type !in HEADSET_MIC_TYPES) {
            commLeftHeadset = true
        }
        check("сразу")
        main.postDelayed({
            if (!done.isCompleted) {
                Log.w(TAG, "маршрут не переключился за ${RELEASE_TIMEOUT_MS} мс, играю как есть; ${state()}")
                done.complete(Unit)
            }
        }, RELEASE_TIMEOUT_MS)
    }

    /** Дождаться, пока после [releaseMic] телефон реально вышел из режима связи (или таймаута). */
    suspend fun awaitRouteSettled() {
        val pending = settled ?: return
        if (!pending.isCompleted) {
            pending.await()
            delay(SETTLE_MS)
        }
        settled = null
    }

    /** Режим телефона, устройство связи и медиа-громкость — для логов. */
    fun state(): String {
        val mode = when (audio.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            else -> audio.mode.toString()
        }
        val comm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) describe(audio.communicationDevice) else "—"
        val volume = "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        return "mode=$mode, связь=$comm, медиа-громкость=$volume"
    }

    @Suppress("DEPRECATION")
    private fun AudioManager.isBluetoothScoOnCompat(): Boolean = isBluetoothScoOn ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    /** Встроенный динамик телефона — для канала «собеседнику», когда подключена гарнитура. */
    fun builtinSpeaker(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Устройства, на которые система сейчас отправит звук с такими атрибутами. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun devicesFor(attributes: AudioAttributes): List<AudioDeviceInfo> =
        audio.getAudioDevicesForAttributes(attributes).mapNotNull { attr ->
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == attr.type && it.address == attr.address }
        }

    fun describe(device: AudioDeviceInfo?): String =
        if (device == null) "по умолчанию" else "${device.productName} (${typeName(device)})"

    fun log(direction: String, what: String): String {
        Log.d(TAG, "$direction: $what")
        return what
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun switchToHeadsetMic(mic: AudioDeviceInfo): Boolean {
        if (micRouted && audio.communicationDevice?.id == mic.id) return true
        val connected = CompletableDeferred<Unit>()
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.id == mic.id) connected.complete(Unit)
        }
        audio.addOnCommunicationDeviceChangedListener(mainExecutor, listener)
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            micRouted = true
            if (!audio.setCommunicationDevice(mic)) return false
            if (audio.communicationDevice?.id == mic.id) connected.complete(Unit)
            return withTimeoutOrNull(SCO_TIMEOUT_MS) { connected.await() } != null
        } finally {
            audio.removeOnCommunicationDeviceChangedListener(listener)
        }
    }

    private fun refresh() {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val output = outputs.firstOrNull { it.type in HEADSET_OUTPUT_TYPES }
        val mic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.firstOrNull { it.type in HEADSET_MIC_TYPES }
        } else {
            null
        }
        val headset = if (output != null || mic != null) {
            Headset(name = (output ?: mic)!!.productName.toString(), mic = mic, output = output)
        } else {
            null
        }
        if (headset?.name != _headset.value?.name || (headset?.mic == null) != (_headset.value?.mic == null)) {
            Log.d(
                TAG,
                if (headset == null) {
                    "гарнитура отключена — звук через телефон"
                } else {
                    "гарнитура «${headset.name}»: микрофон ${headset.mic?.let(::typeName) ?: "нет"}, " +
                        "вывод ${headset.output?.let(::typeName) ?: "нет"}"
                },
            )
        }
        _headset.value = headset
        if (headset?.mic == null && micRouted) releaseMic()
    }

    private fun typeName(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth HFP/SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик телефона"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "разговорный динамик телефона"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "микрофон телефона"
        else -> "тип ${device.type}"
    }

    private companion object {
        const val TAG = "Audio"
        const val SCO_TIMEOUT_MS = 4_000L
        const val RELEASE_TIMEOUT_MS = 3_000L
        const val SETTLE_MS = 200L
        val HEADSET_MIC_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val HEADSET_OUTPUT_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
