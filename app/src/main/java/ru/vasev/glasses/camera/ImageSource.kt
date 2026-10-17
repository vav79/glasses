package ru.vasev.glasses.camera

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.annotation.StringRes
import ru.vasev.glasses.BuildConfig
import ru.vasev.glasses.R
import ru.vasev.glasses.glasses.GlassesManager

/** Откуда берётся снимок для «Что я вижу». [chatLabelRes] — метка у снимка в чате, [logName] — имя в логах. */
enum class ImageSource(
    @StringRes val titleRes: Int,
    @StringRes val chatLabelRes: Int,
    val logName: String,
    val debugOnly: Boolean = false,
) {
    GLASSES(R.string.source_glasses, R.string.source_label_glasses, "glasses"),
    PHONE_CAMERA(R.string.source_phone_camera, R.string.source_label_phone, "phone"),
    GALLERY(R.string.source_gallery, R.string.source_label_gallery, "gallery"),
    SIMULATOR(R.string.source_simulator, R.string.source_label_simulator, "simulator", debugOnly = true),
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
