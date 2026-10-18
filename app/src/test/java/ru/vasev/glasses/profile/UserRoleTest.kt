package ru.vasev.glasses.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vasev.glasses.ui.AppTab

class UserRoleTest {

    @Test
    fun `start tabs`() {
        assertEquals(AppTab.TRANSLATE, UserRole.TRAVELER.startTab)
        assertEquals(AppTab.ASSISTANT, UserRole.RESEARCHER.startTab)
        assertEquals(AppTab.ASSISTANT, UserRole.DEVELOPER.startTab)
    }

    @Test
    fun `everyone has assistant, translation and settings - only developer has development`() {
        for (role in UserRole.entries) {
            assertTrue(role.tabs.containsAll(listOf(AppTab.ASSISTANT, AppTab.CAMERA, AppTab.TRANSLATE)))
            assertEquals(role == UserRole.DEVELOPER, AppTab.DEVELOPMENT in role.tabs)
            assertEquals(role == UserRole.DEVELOPER, role.advancedAlwaysVisible)
        }
    }

    @Test
    fun `switching role keeps the open tab when it exists`() {
        assertEquals(AppTab.CAMERA, UserRole.TRAVELER.keep(AppTab.CAMERA))
        assertEquals(AppTab.TRANSLATE, UserRole.TRAVELER.keep(AppTab.DEVELOPMENT))
        assertEquals(AppTab.DEVELOPMENT, UserRole.DEVELOPER.keep(AppTab.DEVELOPMENT))
    }

    @Test
    fun `unknown saved role falls back to default`() {
        assertEquals(UserRole.DEFAULT, UserRole.restore(null))
        assertEquals(UserRole.DEFAULT, UserRole.restore("PILOT"))
        assertEquals(UserRole.TRAVELER, UserRole.restore("TRAVELER"))
        assertFalse(UserRole.DEFAULT.advancedAlwaysVisible)
    }
}
