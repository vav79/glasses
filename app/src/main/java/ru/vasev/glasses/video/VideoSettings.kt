package ru.vasev.glasses.video

import android.content.Context
import androidx.annotation.StringRes
import com.meta.wearable.dat.camera.types.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.vasev.glasses.R

/** Размер видео с очков (вертикальный, 9:16). При слабом Bluetooth очки сами снижают размер и частоту. */
enum class VideoSize(val quality: VideoQuality, val width: Int, val height: Int, @StringRes val labelRes: Int) {
    P720(VideoQuality.HIGH, 720, 1280, R.string.video_size_720),
    P504(VideoQuality.MEDIUM, 504, 896, R.string.video_size_504),
    P360(VideoQuality.LOW, 360, 640, R.string.video_size_360),
}

/**
 * Звук записи. [AUTO] — микрофон, который сейчас для ввода в системе: подключены очки или гарнитура — их
 * (по Bluetooth, качество звонка, на время записи очки «в звонке»), иначе — телефон.
 */
enum class VideoAudio(@StringRes val labelRes: Int) {
    AUTO(R.string.video_audio_auto),
    PHONE(R.string.video_audio_phone),
    NONE(R.string.video_audio_none),
}

/** Настройки записи видео (SharedPreferences `video`): размер — 720p, звук — «Авто» по умолчанию. */
class VideoSettings(context: Context) {

    private val prefs = context.getSharedPreferences("video", Context.MODE_PRIVATE)

    private val _size = MutableStateFlow(VideoSize.entries.firstOrNull { it.name == prefs.getString(KEY_SIZE, null) } ?: VideoSize.P720)
    val size: StateFlow<VideoSize> = _size.asStateFlow()

    private val _audio = MutableStateFlow(VideoAudio.entries.firstOrNull { it.name == prefs.getString(KEY_AUDIO, null) } ?: VideoAudio.AUTO)
    val audio: StateFlow<VideoAudio> = _audio.asStateFlow()

    fun setSize(value: VideoSize) {
        prefs.edit().putString(KEY_SIZE, value.name).apply()
        _size.value = value
    }

    fun setAudio(value: VideoAudio) {
        prefs.edit().putString(KEY_AUDIO, value.name).apply()
        _audio.value = value
    }

    private companion object {
        const val KEY_SIZE = "size"
        const val KEY_AUDIO = "audio"
    }
}
