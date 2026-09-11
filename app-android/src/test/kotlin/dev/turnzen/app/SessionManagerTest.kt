package dev.turnzen.app

import dev.turnzen.app.data.AuthState
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SessionManagerTest {
    @Test
    fun `sign in stores credentials, session and user`() =
        runTest {
            val store = InMemoryCredentialStore()
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0), store)
            session.signIn(Credentials("demo", "pw"))
            assertIs<AuthState.SignedIn>(session.state.value)
            assertEquals(Credentials("demo", "pw"), store.credentials)
            assertNotNull(store.session)
            assertEquals("demo", store.user?.handle)
        }

    @Test
    fun `restore without stored session is signed out`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.restore()
            assertIs<AuthState.SignedOut>(session.state.value)
        }

    @Test
    fun `restore with a dead session silently signs in again`() =
        runTest {
            val store = InMemoryCredentialStore()
            store.credentials = Credentials("demo", "pw")
            store.session = "stale"
            store.user = SignedInUser("1", "demo", null, null)
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0), store)
            session.restore()
            assertIs<AuthState.SignedIn>(session.state.value)
            assertEquals("demo", store.session)
        }

    @Test
    fun `restore with a changed password signs out with a reason`() =
        runTest {
            val store = InMemoryCredentialStore()
            store.credentials = Credentials("demo", "wrong")
            store.session = "stale"
            store.user = SignedInUser("1", "demo", null, null)
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0), store)
            session.restore()
            val state = session.state.value
            assertIs<AuthState.SignedOut>(state)
            assertNotNull(state.reason)
        }

    @Test
    fun `call retries once after the session expired`() =
        runTest {
            val store = InMemoryCredentialStore()
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val session = sessionFor(client, store)
            session.signIn(Credentials("demo", "pw"))
            client.restoreSession("gone")
            val games = session.call { it.gamesAwaitingMe() }
            assertEquals(3, games.size)
        }

    @Test
    fun `concurrent calls re-sign-in only once after the session expired`() =
        runTest {
            val store = InMemoryCredentialStore()
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            var signIns = 0
            val counting =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun signIn(credentials: Credentials): SignedInUser {
                        signIns++
                        return fake.signIn(credentials)
                    }
                }
            val session = sessionFor(counting, store)
            session.signIn(Credentials("demo", "pw"))
            signIns = 0
            // The stored session dies, then the Games screen fires its two calls at once;
            // both hit the dead session before either can re-login.
            counting.restoreSession("gone")

            val (turn, running) =
                coroutineScope {
                    val a = async { session.call { it.gamesAwaitingMe() } }
                    val b = async { session.call { it.myRunningGames() } }
                    a.await() to b.await()
                }

            assertEquals(1, signIns) // one shared re-login, not one per call
            assertEquals(3, turn.size)
            assertEquals(4, running.size)
            assertIs<AuthState.SignedIn>(session.state.value)
        }

    @Test
    fun `call without stored credentials surfaces SessionExpired`() =
        runTest {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val session = sessionFor(client)
            assertFailsWith<GoServerException.SessionExpired> { session.call { it.gamesAwaitingMe() } }
            assertIs<AuthState.SignedOut>(session.state.value)
        }

    @Test
    fun `sign out clears everything`() =
        runTest {
            val store = InMemoryCredentialStore()
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0), store)
            session.signIn(Credentials("demo", "pw"))
            session.signOut()
            assertIs<AuthState.SignedOut>(session.state.value)
            assertEquals(null, store.credentials)
            assertEquals(null, store.session)
        }
}
