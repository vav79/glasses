package ru.vasev.glasses.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Итог проверки ключа. */
enum class KeyCheckResult { OK, REJECTED, NO_NETWORK, ERROR }

/**
 * Проверка ключа при вводе — запросом списка моделей: он у Anthropic и DeepSeek бесплатный
 * (токены не тратятся). 200 — ключ работает, 401/403 — не подходит; ключ в лог не пишется.
 */
object KeyCheck {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(provider: ProviderId, key: String): KeyCheckResult = withContext(Dispatchers.IO) {
        val request = when (provider) {
            ProviderId.ANTHROPIC -> Request.Builder()
                .url("https://api.anthropic.com/v1/models?limit=1")
                .header("x-api-key", key.trim())
                .header("anthropic-version", "2023-06-01")
            ProviderId.DEEPSEEK, ProviderId.OPENAI -> {
                val base = if (provider == ProviderId.DEEPSEEK) ProviderConfigs.DEEPSEEK.baseUrl else ProviderConfigs.OPENAI.baseUrl
                Request.Builder().url(base.trimEnd('/') + "/models").header("Authorization", "Bearer ${key.trim()}")
            }
        }.get().build()
        try {
            http.newCall(request).execute().use { response ->
                val result = when (response.code) {
                    200 -> KeyCheckResult.OK
                    401, 403 -> KeyCheckResult.REJECTED
                    else -> KeyCheckResult.ERROR
                }
                Log.d(TAG, "key check ${provider.title}: http=${response.code} result=$result")
                result
            }
        } catch (e: IOException) {
            Log.d(TAG, "key check ${provider.title}: ${e.javaClass.simpleName}")
            KeyCheckResult.NO_NETWORK
        }
    }

    private const val TAG = "LLM"
}
