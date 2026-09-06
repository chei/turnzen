package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamesStoreTest {
    @Test
    fun `both lists load, sorted by most recent move first`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(session)
            store.refresh()

            assertEquals(listOf("demo-19", "demo-13", "demo-9"), store.yourTurn.value?.map { it.id.value })
            // "All games" also includes the opponent-to-move game.
            assertTrue(store.allRunning.value!!.any { it.id.value == "demo-waiting" })
            assertEquals(store.yourTurn.value!!.size + 1, store.allRunning.value!!.size)
            assertTrue(store.yourTurn.value!!.all { it.isMyTurn })
            assertTrue(store.allRunning.value!!.any { !it.isMyTurn })
        }

    @Test
    fun `auto-advance walks the your-turn queue and wraps`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(session)
            store.refresh()

            assertEquals(GameId("demo-13"), store.nextAfter(GameId("demo-19")))
            store.remove(GameId("demo-19"))
            assertEquals(GameId("demo-9"), store.nextAfter(GameId("demo-13")))
            store.remove(GameId("demo-13"))
            store.remove(GameId("demo-9"))
            assertNull(store.nextAfter(GameId("demo-9")))
        }

    @Test
    fun `remove drops a game from both lists`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(session)
            store.refresh()
            store.remove(GameId("demo-19"))
            assertTrue(store.yourTurn.value!!.none { it.id.value == "demo-19" })
            assertTrue(store.allRunning.value!!.none { it.id.value == "demo-19" })
        }
}
