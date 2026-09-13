package dev.turnzen.app

import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SettingsStore
import dev.turnzen.app.data.ThemeMode
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.settings.SettingsViewModel
import dev.turnzen.goserver.Credentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainDispatcherTest() {
    private val prefs = FakeSharedPreferences()

    @Test
    fun `a board setting changes only that setting`() =
        runTest(dispatcher) {
            val store = SettingsStore(prefs)
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val vm = SettingsViewModel(session, store, GamesStore(session))
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
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val vm = SettingsViewModel(session, SettingsStore(prefs), GamesStore(session))

            vm.setTheme(ThemeMode.LIGHT)
            vm.updateBoard { it.copy(numberTrialStones = false) }

            val afterRestart = SettingsStore(prefs).settings.value
            assertEquals(ThemeMode.LIGHT, afterRestart.theme)
            assertFalse(afterRestart.board.numberTrialStones)
        }

    @Test
    fun `signing out drops the cached games as well as the session`() =
        runTest(dispatcher) {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val games = GamesStore(session)
            games.refresh()
            assertTrue(!games.yourTurn.value.isNullOrEmpty())
            val vm = SettingsViewModel(session, SettingsStore(prefs), games)

            vm.signOut()
            advanceUntilIdle()

            assertIs<AuthState.SignedOut>(session.state.value)
            // Otherwise the next account to sign in on this phone would briefly see the
            // previous one's games.
            assertNull(games.yourTurn.value)
            assertNull(games.allRunning.value)
        }

    @Test
    fun `the handle has a placeholder while no one is signed in`() =
        runTest(dispatcher) {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val vm = SettingsViewModel(session, SettingsStore(prefs), GamesStore(session))

            assertEquals("–", vm.userHandle)

            session.signIn(Credentials("demo", "pw"))

            assertEquals("demo", vm.userHandle)
        }

    @Test
    fun `the issue link is a usable url, not raw text`() =
        runTest(dispatcher) {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val vm = SettingsViewModel(session, SettingsStore(prefs), GamesStore(session))

            val url = vm.issuesUrl

            assertTrue(url.startsWith(vm.sourceUrl + "/issues/new?body="), url)
            // The body carries newlines and spaces; unencoded they would truncate the link.
            assertFalse(url.contains(' '), url)
            assertFalse(url.contains('\n'), url)
        }
}
