package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.games.GamesTab
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
    /** Counts the list calls, to show which tab costs which request. */
    private class CountingClient(
        private val fake: FakeTurnBasedGoServerClient = FakeTurnBasedGoServerClient(latencyMillis = 0),
    ) : TurnBasedGoServerClient by fake {
        var runningCalls = 0
        var finishedCalls = 0

        override suspend fun myRunningGames(): List<GameSummary> {
            runningCalls++
            return fake.myRunningGames()
        }

        override suspend fun myFinishedGames(limit: Int): List<GameSummary> {
            finishedCalls++
            return fake.myFinishedGames(limit)
        }
    }

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
    fun `the archive is fetched when its tab is first shown, and not again`() =
        runTest(dispatcher) {
            val client = CountingClient()
            val session = sessionFor(client)
            session.signIn(Credentials("demo", "pw"))
            val vm = GamesViewModel(session, GamesStore(session))
            backgroundScope.launch { vm.state.collect {} }

            // The running lists are on screen; the archive has not been asked for.
            vm.onTabShown(GamesTab.YOUR_TURN)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(0, client.finishedCalls)
            assertNull(vm.state.value.finished)

            vm.onTabShown(GamesTab.FINISHED)
            advanceUntilIdle()
            assertEquals(1, client.finishedCalls)
            val finished = assertNotNull(vm.state.value.finished)
            assertTrue(finished.isNotEmpty())
            assertEquals(finished, vm.state.value.listFor(GamesTab.FINISHED))

            // Coming back to the tab asks for nothing: a finished game does not change.
            vm.onTabShown(GamesTab.FINISHED)
            advanceUntilIdle()
            assertEquals(1, client.finishedCalls)

            // Pulling to refresh on that tab does ask again — and only for the archive.
            val listCalls = client.runningCalls
            vm.refresh(GamesTab.FINISHED)
            advanceUntilIdle()
            assertEquals(2, client.finishedCalls)
            assertEquals(listCalls, client.runningCalls)
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
