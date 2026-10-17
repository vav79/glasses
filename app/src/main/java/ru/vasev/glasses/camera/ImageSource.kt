package ru.vasev.glasses.camera

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.glasses.GlassesManager

/** Откуда берётся снимок для «Что я вижу». [chatLabel] — метка у снимка в чате. */
enum class ImageSource(val title: String, val chatLabel: String, val debugOnly: Boolean = false) {
    GLASSES("Очки", "очки"),
    PHONE_CAMERA("Камера телефона", "телефон"),
    GALLERY("Из галереи", "галерея"),
    SIMULATOR("Симулятор очков", "симулятор", debugOnly = true),
    ;

    /** Снимок даёт DAT SDK (настоящие очки или MockDeviceKit). */
    val usesGlasses: Boolean get() = this == GLASSES || this == SIMULATOR

    companion object {
        val DEFAULT = GLASSES

        /** Симулятор очков — только в debug-сборке. */
        fun available(debug: Boolean): List<ImageSource> = entries.filter { debug || !it.debugOnly }

        /** Сохранённое значение; недоступное в этой сборке (симулятор в release) — источник по умолчанию. */
        fun restore(name: String?, debug: Boolean): ImageSource =
            entries.firstOrNull { it.name == name }?.takeIf { it in available(debug) } ?: DEFAULT
    }
}

/** Выбранный источник снимков. Режим MockDeviceKit включается только при выборе «Симулятор очков». */
class ImageSourceSettings(context: Context, private val glasses: GlassesManager) {

    private val prefs = context.getSharedPreferences("image_source", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(ImageSource.restore(prefs.getString(KEY, null), BuildConfig.DEBUG))
    val source: StateFlow<ImageSource> = _source.asStateFlow()

    val available: List<ImageSource> = ImageSource.available(BuildConfig.DEBUG)

    init {
        applyGlassesMode(_source.value)
    }

    fun set(source: ImageSource) {
        if (source !in available) return
        prefs.edit().putString(KEY, source.name).apply()
        _source.value = source
        applyGlassesMode(source)
    }

    /** Симулятор — включить MockDeviceKit; очки — выключить. Камера телефона и галерея режим очков не трогают. */
    private fun applyGlassesMode(source: ImageSource) {
        when (source) {
            ImageSource.SIMULATOR -> glasses.setMockMode(true)
            ImageSource.GLASSES -> glasses.setMockMode(false)
            ImageSource.PHONE_CAMERA, ImageSource.GALLERY -> Unit
        }
    }

    private companion object {
        const val KEY = "source"
    }
}
