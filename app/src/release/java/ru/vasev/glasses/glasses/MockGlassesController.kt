package ru.vasev.glasses.glasses

import android.content.Context

/**
 * Release-заглушка симулятора очков: MockDeviceKit (`mwdat-mockdevice`) подключён только к debug-сборке
 * (`debugImplementation`), настоящий контроллер — в `src/debug`. В release симулятор не выбрать
 * (`ImageSource.available(BuildConfig.DEBUG)`), так что эти методы не вызываются.
 */
@Suppress("UNUSED_PARAMETER")
class MockGlassesController(context: Context) {
    suspend fun enable() = Unit

    fun simulateVoiceLaunch(): Boolean = false

    fun disable() = Unit
}