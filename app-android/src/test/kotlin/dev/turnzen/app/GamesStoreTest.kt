package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            store.onMovePlayed(GameId("demo-19"))
            assertEquals(GameId("demo-9"), store.nextAfter(GameId("demo-13")))
            store.onMovePlayed(GameId("demo-13"))
            store.onMovePlayed(GameId("demo-9"))
            assertNull(store.nextAfter(GameId("demo-9")))
        }

    @Test
    fun `a played move leaves the your-turn queue but stays in all games`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(session)
            store.refresh()
            store.onMovePlayed(GameId("demo-19"))

            // Gone from the "Your turn" queue.
            assertTrue(store.yourTurn.value!!.none { it.id.value == "demo-19" })

            // Still a running game, now shown as the opponent's turn.
            val stillRunning = store.allRunning.value!!.firstOrNull { it.id.value == "demo-19" }
            assertNotNull(stillRunning)
            assertFalse(stillRunning.isMyTurn)
        }
}
