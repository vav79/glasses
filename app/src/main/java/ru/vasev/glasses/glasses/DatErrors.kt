package ru.vasev.glasses.glasses

import androidx.annotation.StringRes
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import ru.vasev.glasses.R

/** Коды ошибок DAT SDK → текст для пользователя. Коды не сводятся в одно «несовместимо»: лечатся они по-разному. */
object DatErrors {

    /** Текст ошибки сессии; null — не мешает съёмке (версии вне рекомендованного диапазона, сессия продолжается). */
    @StringRes
    fun session(error: DeviceSessionError): Int? = when (error) {
        DeviceSessionError.NO_ELIGIBLE_DEVICE,
        DeviceSessionError.DEVICE_DISCONNECTED -> R.string.glasses_err_not_connected
        // Отдельного кода «занято другим приложением» нет: это самый близкий — у очков уже есть активная сессия.
        DeviceSessionError.SESSION_ALREADY_EXISTS -> R.string.glasses_err_busy
        DeviceSessionError.SESSION_ENDED_BY_DEVICE -> R.string.glasses_err_ended_by_device
        DeviceSessionError.CAPABILITY_DENIED -> R.string.glasses_err_camera_permission
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> R.string.glasses_err_update_dat
        DeviceSessionError.DWA_UNAVAILABLE -> R.string.glasses_err_open_meta_ai
        DeviceSessionError.INSUFFICIENT_SDK_VERSION -> R.string.glasses_err_app_outdated
        DeviceSessionError.THERMAL_CRITICAL,
        DeviceSessionError.THERMAL_EMERGENCY -> R.string.glasses_err_overheated
        DeviceSessionError.BATTERY_CRITICAL,
        DeviceSessionError.PEAK_POWER_SHUTDOWN -> R.string.glasses_err_battery
        DeviceSessionError.DWA_OUT_OF_STU_RANGE -> null
        DeviceSessionError.SESSION_ALREADY_STOPPED,
        DeviceSessionError.SESSION_IDLE,
        DeviceSessionError.CAPABILITY_ALREADY_ADDED,
        DeviceSessionError.CAPABILITY_NOT_FOUND,
        DeviceSessionError.UNEXPECTED_ERROR -> R.string.glasses_err_session
    }

    /** Подсказка после неудачной регистрации; null — показывать нечего («уже зарегистрировано» и т. п.). */
    @StringRes
    fun registration(error: RegistrationError): Int? = when (error) {
        RegistrationError.META_AI_NOT_INSTALLED -> R.string.glasses_reg_install_meta_ai
        RegistrationError.FAILED_TO_REGISTER,
        RegistrationError.UNKNOWN -> R.string.glasses_reg_failed
        RegistrationError.ALREADY_REGISTERED,
        RegistrationError.ALREADY_UNREGISTERED,
        RegistrationError.FAILED_TO_UNREGISTER -> null
    }
}
