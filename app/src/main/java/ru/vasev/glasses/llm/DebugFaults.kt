package ru.vasev.glasses.llm

import android.util.Log
import ru.vasev.glasses.BuildConfig

/**
 * Только для debug-сборки: имитация сбоя провайдера, чтобы проверить запасную модель без настоящего сбоя Claude.
 * `adb shell "am start -n ru.vasev.glasses/.MainActivity --es fail_claude server"` (server|network|forbidden|ratelimit)
 * — следующий запрос к Claude завершится такой ошибкой.
 */
object DebugFaults {

    @Volatile
    private var pending: Pair<ProviderId, ProviderException.Kind>? = null

    fun arm(provider: ProviderId, kind: String) {
        if (!BuildConfig.DEBUG) return
        val parsed = when (kind) {
            "server" -> ProviderException.Kind.SERVER
            "network" -> ProviderException.Kind.NETWORK
            "forbidden" -> ProviderException.Kind.FORBIDDEN
            "ratelimit" -> ProviderException.Kind.RATE_LIMIT
            else -> return
        }
        pending = provider to parsed
        Log.d("LLM", "debug: the next ${provider.title} request will fail with $parsed")
    }

    fun consume(provider: ProviderId): ProviderException? {
        if (!BuildConfig.DEBUG) return null
        val fault = pending?.takeIf { it.first == provider } ?: return null
        pending = null
        val status = when (fault.second) {
            ProviderException.Kind.SERVER -> 529
            ProviderException.Kind.FORBIDDEN -> 403
            ProviderException.Kind.RATE_LIMIT -> 429
            else -> null
        }
        return ProviderException(provider, fault.second, status, detail = "simulated (debug)")
    }
}
