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
    fun `every role has the same four tabs, at most four - development lives in settings`() {
        for (role in UserRole.entries) {
            assertEquals(AppTab.entries.toSet(), role.tabs.toSet())
            assertTrue(role.tabs.size <= 4)
            assertEquals(role == UserRole.DEVELOPER, role.advancedAlwaysVisible)
        }
    }

    @Test
    fun `switching role keeps the open tab when it exists`() {
        assertEquals(AppTab.CAMERA, UserRole.TRAVELER.keep(AppTab.CAMERA))
        assertEquals(AppTab.NAVIGATION, UserRole.DEVELOPER.keep(AppTab.NAVIGATION))
        // Путешественнику навигация — второй вкладкой, сразу после перевода.
        assertEquals(AppTab.NAVIGATION, UserRole.TRAVELER.tabs[1])
    }

    @Test
    fun `unknown saved role falls back to default`() {
        assertEquals(UserRole.DEFAULT, UserRole.restore(null))
        assertEquals(UserRole.DEFAULT, UserRole.restore("PILOT"))
        assertEquals(UserRole.TRAVELER, UserRole.restore("TRAVELER"))
        assertFalse(UserRole.DEFAULT.advancedAlwaysVisible)
    }
}
