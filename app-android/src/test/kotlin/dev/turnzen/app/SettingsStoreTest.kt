package dev.turnzen.app

import dev.turnzen.app.data.CheckInterval
import dev.turnzen.app.data.NotificationSettings
import dev.turnzen.app.data.SettingsStore
import dev.turnzen.app.data.ThemeMode
import dev.turnzen.dgs.DgsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Test
    fun `a first run gets the defaults`() {
        val settings = SettingsStore(FakeSharedPreferences()).settings.value

        assertEquals(DgsClient.DEFAULT_BASE_URL, settings.serverUrl)
        assertTrue(settings.board.showCoordinates)
        assertTrue(settings.board.markLastMove)
        assertTrue(settings.board.numberTrialStones)
        assertEquals(ThemeMode.SYSTEM, settings.theme)
        assertTrue(settings.playerIcons)
    }

    @Test
    fun `turning player icons off survives a restart`() {
        val prefs = FakeSharedPreferences()
        SettingsStore(prefs).update { it.copy(playerIcons = false) }
        assertFalse(SettingsStore(prefs).settings.value.playerIcons)
    }

    @Test
    fun `an update is visible immediately and survives a restart`() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)

        store.update { it.copy(board = it.board.copy(showCoordinates = false), theme = ThemeMode.DARK) }

        assertFalse(store.settings.value.board.showCoordinates)
        assertEquals(ThemeMode.DARK, store.settings.value.theme)

        val afterRestart = SettingsStore(prefs).settings.value
        assertFalse(afterRestart.board.showCoordinates)
        assertEquals(ThemeMode.DARK, afterRestart.theme)
        // Untouched settings keep their values rather than falling back to the defaults.
        assertTrue(afterRestart.board.markLastMove)
    }

    @Test
    fun `choosing a server changes the url the session layer reads`() {
        val store = SettingsStore(FakeSharedPreferences())

        store.selectServer("https://dgs.example.test/")

        assertEquals("https://dgs.example.test/", store.serverUrl)
        assertEquals("https://dgs.example.test/", store.settings.value.serverUrl)
    }

    @Test
    fun `a theme value the app no longer knows falls back to following the system`() {
        // A downgrade, or a renamed enum constant: the stored string must never crash the
        // app on start, and "follow the system" is the safe reading.
        val store = SettingsStore(FakeSharedPreferences(mapOf("theme_mode" to "SEPIA")))

        assertEquals(ThemeMode.SYSTEM, store.settings.value.theme)
    }

    @Test
    fun `the state flow emits a new value on every update`() {
        val store = SettingsStore(FakeSharedPreferences())
        val first = store.settings.value

        store.update { it.copy(theme = ThemeMode.LIGHT) }

        // StateFlow only notifies on a changed value, so the data class must be copied,
        // not mutated in place.
        assertFalse(first === store.settings.value)
        assertEquals(ThemeMode.SYSTEM, first.theme)
    }

    @Test
    fun `notifications start off and keep what the user chose`() {
        val prefs = FakeSharedPreferences()
        val store = SettingsStore(prefs)
        assertEquals(NotificationSettings(), store.settings.value.notifications)
        assertFalse(store.settings.value.notifications.anyOn)

        store.update {
            it.copy(
                notifications = NotificationSettings(lowTime = true, checkEvery = CheckInterval.HOUR_3),
            )
        }

        val afterRestart = SettingsStore(prefs).settings.value.notifications
        assertEquals(NotificationSettings(lowTime = true, checkEvery = CheckInterval.HOUR_3), afterRestart)
    }
}
