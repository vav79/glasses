package ru.vasev.glasses.glasses

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import ru.vasev.glasses.GlassesApp.Companion.TAG

/**
 * «Hey Meta, start Glasses Helper»: Meta AI запускает приложение и присылает [LaunchApp] через поток голосовых запусков.
 * Поток один на всё приложение и живёт, пока приложение зарегистрировано в Meta AI. Событие ждёт экран ([pending]):
 * «Ассистент» начинает слушать вопрос и отвечает Meta AI ([respond]). На каждое событие — ровно один ответ:
 * не забрали за [CLAIM_TIMEOUT_MS] (экран не показался) — отказ, иначе Meta AI ждала бы и показала бы запасной экран.
 * Имя для «Hey Meta» и разрешение Voice Invocation задаются в Wearables Developer Center, не в манифесте.
 */
class VoiceLaunch(private val scope: CoroutineScope) {

    private var stream: VoiceInvocationsStream? = null
    private val jobs = mutableListOf<Job>()

    private val _pending = MutableStateFlow<LaunchApp?>(null)

    /** Запуск голосом, который ещё не забрал экран. */
    val pending: StateFlow<LaunchApp?> = _pending.asStateFlow()

    init {
        // Поток имеет смысл только у зарегистрированного приложения; после повторной регистрации — новый поток.
        scope.launch {
            Wearables.registrationState.collect { state ->
                if (state == RegistrationState.REGISTERED) start() else close()
            }
        }
    }

    /** Забрать запуск, чтобы ответить на него; null — уже забрали или отказали по таймауту. */
    fun take(): LaunchApp? = _pending.getAndUpdate { null }

    fun respond(launch: LaunchApp, ok: Boolean, reason: String = "") {
        scope.launch {
            val delivered = if (ok) launch.responseHandle.sendSuccess(null) else launch.responseHandle.sendFailure(reason)
            Log.d(TAG, "VoiceInvocation: answered ${if (ok) "success" else "failure ($reason)"} delivered=$delivered")
        }
    }

    private fun start() {
        if (stream != null) return
        val s = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
        stream = s
        jobs += scope.launch { s.state.collect { Log.d(TAG, "VoiceInvocation: state=$it") } }
        jobs += scope.launch { s.errors.collect { Log.w(TAG, "VoiceInvocation: error=$it") } }
        jobs += scope.launch {
            s.invocations.collect { invocation ->
                when (invocation) {
                    is LaunchApp -> onLaunch(invocation)
                    else -> Log.w(TAG, "VoiceInvocation: unsupported ${invocation.javaClass.simpleName}")
                }
            }
        }
    }

    private fun onLaunch(launch: LaunchApp) {
        Log.d(TAG, "VoiceInvocation: LaunchApp")
        // Два запуска подряд, а первый ещё не забран: ответить на первый, чтобы Meta AI не ждала.
        _pending.getAndUpdate { launch }?.let { respond(it, ok = false, reason = "superseded by a newer launch") }
        scope.launch {
            delay(CLAIM_TIMEOUT_MS)
            if (_pending.compareAndSet(launch, null)) respond(launch, ok = false, reason = "app screen did not open")
        }
    }

    private fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        stream?.close()
        stream = null
        take()?.let { respond(it, ok = false, reason = "app is not registered") }
    }

    private companion object {
        const val CLAIM_TIMEOUT_MS = 10_000L
    }
}
