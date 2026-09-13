package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.games.GamesViewModel
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GamesViewModelTest : MainDispatcherTest() {
    /** Signs in fine (the session is stored), then fails every list call while [offline]. */
    private class FlakyClient(
        private val fake: FakeTurnBasedGoServerClient = FakeTurnBasedGoServerClient(latencyMillis = 0),
        var offline: Boolean = true,
    ) : TurnBasedGoServerClient by fake {
        override suspend fun gamesAwaitingMe(): List<GameSummary> {
            if (offline) throw GoServerException.Unreachable("no network")
            return fake.gamesAwaitingMe()
        }

        override suspend fun myRunningGames(): List<GameSummary> {
            if (offline) throw GoServerException.Unreachable("no network")
            return fake.myRunningGames()
        }
    }

    @Test
    fun `an unreachable server is reported as something to wait out, not as an error`() =
        runTest(dispatcher) {
            val client = FlakyClient()
            val session = sessionFor(client)
            session.signIn(Credentials("demo", "pw"))
            val vm = GamesViewModel(session, GamesStore(session))
            // The state flow only runs while something collects it.
            backgroundScope.launch { vm.state.collect {} }

            vm.refresh()
            advanceUntilIdle()

            val s = vm.state.value
            assertNull(s.yourTurn)
            assertFalse(s.refreshing)
            val problem = assertNotNull(s.problem)
            assertTrue(problem.transient)
            assertEquals("Can't reach ${session.serverName}", problem.title)
        }

    @Test
    fun `retrying once the server answers clears the problem and fills the list`() =
        runTest(dispatcher) {
            val client = FlakyClient()
            val session = sessionFor(client)
            session.signIn(Credentials("demo", "pw"))
            val vm = GamesViewModel(session, GamesStore(session))
            backgroundScope.launch { vm.state.collect {} }

            vm.refresh()
            advanceUntilIdle()
            assertNotNull(vm.state.value.problem)

            client.offline = false
            vm.refresh()
            advanceUntilIdle()

            val s = vm.state.value
            assertNull(s.problem)
            assertTrue(!s.yourTurn.isNullOrEmpty())
        }
}
