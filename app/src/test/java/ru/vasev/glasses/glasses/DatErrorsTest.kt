package ru.vasev.glasses.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.vasev.glasses.R

class DatErrorsTest {

    @Test
    fun `версии и занятость — разные тексты, а не одно «несовместимо»`() {
        assertEquals(R.string.glasses_err_update_dat, DatErrors.session(DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED))
        assertEquals(R.string.glasses_err_app_outdated, DatErrors.session(DeviceSessionError.INSUFFICIENT_SDK_VERSION))
        assertEquals(R.string.glasses_err_open_meta_ai, DatErrors.session(DeviceSessionError.DWA_UNAVAILABLE))
        assertEquals(R.string.glasses_err_busy, DatErrors.session(DeviceSessionError.SESSION_ALREADY_EXISTS))
    }

    @Test
    fun `очки не подключены — при отсутствии устройства и при обрыве`() {
        assertEquals(R.string.glasses_err_not_connected, DatErrors.session(DeviceSessionError.NO_ELIGIBLE_DEVICE))
        assertEquals(R.string.glasses_err_not_connected, DatErrors.session(DeviceSessionError.DEVICE_DISCONNECTED))
    }

    @Test
    fun `версии вне рекомендованного диапазона съёмку не останавливают`() {
        assertNull(DatErrors.session(DeviceSessionError.DWA_OUT_OF_STU_RANGE))
    }

    @Test
    fun `у каждой остальной ошибки сессии есть текст`() {
        DeviceSessionError.entries
            .filter { it != DeviceSessionError.DWA_OUT_OF_STU_RANGE }
            .forEach { assertNotNull(it.name, DatErrors.session(it)) }
    }

    @Test
    fun `регистрация — нет Meta AI, сбой, уже зарегистрировано`() {
        assertEquals(R.string.glasses_reg_install_meta_ai, DatErrors.registration(RegistrationError.META_AI_NOT_INSTALLED))
        assertEquals(R.string.glasses_reg_failed, DatErrors.registration(RegistrationError.FAILED_TO_REGISTER))
        assertEquals(R.string.glasses_reg_failed, DatErrors.registration(RegistrationError.UNKNOWN))
        assertNull(DatErrors.registration(RegistrationError.ALREADY_REGISTERED))
    }
}
