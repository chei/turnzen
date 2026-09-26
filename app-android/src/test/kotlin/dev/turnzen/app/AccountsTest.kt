package dev.turnzen.app

import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.SignInMethod
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two servers side by side: one merged list, and neither server's trouble costing the other. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsTest : MainDispatcherTest() {
    private val ogsId = ServerId("ogs")

    /** A demo server that can be told to stop answering. */
    private class Flaky(
        private val fake: FakeTurnBasedGoServerClient,
    ) : TurnBasedGoServerClient by fake {
        var down = false

        override suspend fun gamesAwaitingMe(): List<GameSummary> {
            if (down) throw GoServerException.Unreachable("down")
            return fake.gamesAwaitingMe()
        }

        override suspend fun myRunningGames(): List<GameSummary> {
            if (down) throw GoServerException.Unreachable("down")
            return fake.myRunningGames()
        }
    }

    private val dgsClient = Flaky(FakeTurnBasedGoServerClient(latencyMillis = 0))
    private val ogsClient =
        Flaky(
            FakeTurnBasedGoServerClient(latencyMillis = 0, serverId = ogsId, serverName = "OGS", browserSignIn = true),
        )

    private suspend fun signedInPair(): Triple<SessionManager, SessionManager, GamesStore> {
        val dgs = sessionFor(dgsClient)
        val ogs = SessionManager(InMemoryCredentialStore(), InMemoryServerSelection()) { ogsClient }
        dgs.signIn(Credentials("demo", "pw"))
        ogs.signInInBrowser { }
        return Triple(dgs, ogs, GamesStore(Accounts(listOf(ogs, dgs))))
    }

    @Test
    fun `games of both servers are merged into one list, newest move first`() =
        runTest(dispatcher) {
            val (_, _, games) = signedInPair()
            val failures = games.refresh()
            assertTrue(failures.isEmpty())
            val running = games.allRunning.value!!
            assertEquals(setOf(DEMO_SERVER, ogsId), running.map { it.id.server }.toSet())
            assertEquals(running.sortedByDescending { it.lastMoveAt }, running)
        }

    @Test
    fun `a server that does not answer keeps its games and is named`() =
        runTest(dispatcher) {
            val (_, _, games) = signedInPair()
            games.refresh()
            val ogsGames = games.allRunning.value!!.filter { it.id.server == ogsId }
            ogsClient.down = true

            val failures = games.refresh()

            assertEquals(setOf(ogsId), failures.keys)
            assertIs<GoServerException.Unreachable>(failures.getValue(ogsId))
            assertEquals(ogsGames, games.allRunning.value!!.filter { it.id.server == ogsId })
        }

    @Test
    fun `when every server fails a first load stays unloaded`() =
        runTest(dispatcher) {
            val (_, _, games) = signedInPair()
            ogsClient.down = true
            dgsClient.down = true

            val failures = games.refresh()

            assertEquals(2, failures.size)
            assertNull(games.allRunning.value, "nothing loaded is not the same as no games")
        }

    @Test
    fun `signing one server out takes only its games away`() =
        runTest(dispatcher) {
            val (dgs, ogs, games) = signedInPair()
            games.refresh()

            games.signOut(ogs)

            assertIs<AuthState.SignedOut>(ogs.state.value)
            assertIs<AuthState.SignedIn>(dgs.state.value)
            assertTrue(games.allRunning.value!!.all { it.id.server == DEMO_SERVER })
            assertTrue(games.allRunning.value!!.isNotEmpty())
        }

    @Test
    fun `a browser sign-in is restored without a password`() =
        runTest(dispatcher) {
            val store = InMemoryCredentialStore()
            val first = SessionManager(store, InMemoryServerSelection()) { ogsClient }
            first.signInInBrowser { }
            assertNull(store.credentials)

            val restarted = SessionManager(store, InMemoryServerSelection()) { ogsClient }
            restarted.restore()

            assertIs<AuthState.SignedIn>(restarted.state.value)
            assertEquals(SignInMethod.BROWSER, restarted.signInMethod)
        }

    @Test
    fun `a lapsed browser sign-in signs out with a reason instead of retrying`() =
        runTest(dispatcher) {
            val store = InMemoryCredentialStore()
            val session = SessionManager(store, InMemoryServerSelection()) { ogsClient }
            session.signInInBrowser { }
            ogsClient.signOut() // the server forgets the session

            val result = runCatching { session.call { it.myRunningGames() } }

            assertIs<GoServerException.SessionExpired>(result.exceptionOrNull())
            val state = assertIs<AuthState.SignedOut>(session.state.value)
            assertTrue(state.reason!!.contains("OGS"))
            assertNull(store.session, "a dead session is not retried on the next launch")
        }
}
