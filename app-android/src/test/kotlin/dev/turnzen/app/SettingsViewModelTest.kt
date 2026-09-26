package dev.turnzen.app

import dev.turnzen.app.data.SettingsStore
import dev.turnzen.app.data.ThemeMode
import dev.turnzen.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainDispatcherTest() {
    private val prefs = FakeSharedPreferences()

    @Test
    fun `a board setting changes only that setting`() =
        runTest(dispatcher) {
            val store = SettingsStore(prefs)
            val vm = SettingsViewModel(store)
            vm.setTheme(ThemeMode.DARK)

            vm.updateBoard { it.copy(showCoordinates = false) }

            val settings = vm.settings.value
            assertFalse(settings.board.showCoordinates)
            assertTrue(settings.board.markLastMove, "the other board settings are untouched")
            assertEquals(ThemeMode.DARK, settings.theme, "and so is the theme")
            assertEquals(store.settings.value, settings, "the screen reads the store's own flow")
        }

    @Test
    fun `settings changed here are the ones the next start reads`() =
        runTest(dispatcher) {
            val vm = SettingsViewModel(SettingsStore(prefs))

            vm.setTheme(ThemeMode.LIGHT)
            vm.updateBoard { it.copy(numberTrialStones = false) }

            val afterRestart = SettingsStore(prefs).settings.value
            assertEquals(ThemeMode.LIGHT, afterRestart.theme)
            assertFalse(afterRestart.board.numberTrialStones)
        }

    @Test
    fun `the issue link is a usable url, not raw text`() =
        runTest(dispatcher) {
            val vm = SettingsViewModel(SettingsStore(prefs))

            val url = vm.issuesUrl

            assertTrue(url.startsWith(vm.sourceUrl + "/issues/new?body="), url)
            // The body carries newlines and spaces; unencoded they would truncate the link.
            assertFalse(url.contains(' '), url)
            assertFalse(url.contains('\n'), url)
        }
}
