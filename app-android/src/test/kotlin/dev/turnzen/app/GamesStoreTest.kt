package dev.turnzen.app

import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.TurnBasedGoServerClient
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
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            assertEquals(
                listOf("demo-19", "demo-scoring", "demo-13", "demo-9"),
                store.yourTurn.value?.map { it.id.value },
            )
            // "All games" also includes the opponent-to-move game.
            assertTrue(store.allRunning.value!!.any { it.id.value == "demo-waiting" })
            assertEquals(store.yourTurn.value!!.size + 1, store.allRunning.value!!.size)
            assertTrue(store.yourTurn.value!!.all { it.isMyTurn })
            assertTrue(store.allRunning.value!!.any { !it.isMyTurn })
        }

    @Test
    fun `auto-advance walks the your-turn queue and stops at the end`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            // your-turn order: demo-19, demo-scoring, demo-13, demo-9
            assertEquals(GameId(DEMO_SERVER, "demo-scoring"), store.nextAfter(GameId(DEMO_SERVER, "demo-19")))
            assertEquals(GameId(DEMO_SERVER, "demo-13"), store.nextAfter(GameId(DEMO_SERVER, "demo-scoring")))
            assertEquals(GameId(DEMO_SERVER, "demo-9"), store.nextAfter(GameId(DEMO_SERVER, "demo-13")))
            // The last game does not wrap around to the first: null means "back to the list".
            assertNull(store.nextAfter(GameId(DEMO_SERVER, "demo-9")))
        }

    @Test
    fun `the archive is fetched on its own and only when asked for`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            // Refreshing the running lists costs nothing in the archive: it stays unfetched.
            assertNull(store.finished.value)

            store.refreshFinished()
            val finished = assertNotNull(store.finished.value)
            assertEquals(listOf("demo-done-win", "demo-done-loss"), finished.map { it.id.value })
            assertTrue(finished.all { it.isFinished })
            assertTrue(finished.none { it.isMyTurn })
            // The board opens a finished game from the list like any other.
            assertNotNull(store.summary(GameId(DEMO_SERVER, "demo-done-win")))
        }

    @Test
    fun `a game that ends drops the archive, so the next look at it includes the game`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()
            store.refreshFinished()

            store.onGameEnded(GameId(DEMO_SERVER, "demo-19"))
            assertNull(store.finished.value)
        }

    @Test
    fun `a played move leaves the your-turn queue but stays in all games`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()
            store.onMovePlayed(GameId(DEMO_SERVER, "demo-19"))

            // Gone from the "Your turn" queue.
            assertTrue(store.yourTurn.value!!.none { it.id.value == "demo-19" })

            // Still a running game, now shown as the opponent's turn.
            val stillRunning = store.allRunning.value!!.firstOrNull { it.id.value == "demo-19" }
            assertNotNull(stillRunning)
            assertFalse(stillRunning.isMyTurn)
        }

    @Test
    fun `the first load is not an arrival, and an unchanged refresh brings none`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))

            // Every row is new on the first load; lighting them all up would say nothing.
            store.refresh()
            assertTrue(
                store.arrivals.value.yourTurn
                    .isEmpty(),
            )
            assertTrue(
                store.arrivals.value.allRunning
                    .isEmpty(),
            )

            store.refresh()
            assertTrue(
                store.arrivals.value.yourTurn
                    .isEmpty(),
            )
            assertTrue(
                store.arrivals.value.allRunning
                    .isEmpty(),
            )
        }

    @Test
    fun `a game that rejoins the your-turn queue arrives, and is shown only once`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            // Stand in for the opponent moving: the game leaves the queue, then the next
            // refresh finds it waiting on the user again.
            store.onMovePlayed(GameId(DEMO_SERVER, "demo-19"))
            store.refresh()
            assertEquals(setOf(GameId(DEMO_SERVER, "demo-19")), store.arrivals.value.yourTurn)

            // The highlight plays once: having been shown, it is not pending any more.
            store.onArrivalShown(GameId(DEMO_SERVER, "demo-19"))
            assertTrue(
                store.arrivals.value.yourTurn
                    .isEmpty(),
            )
        }

    @Test
    fun `a move only counts as news when it leaves the game waiting on the user`() =
        runTest {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            var refreshes = 0
            // After the first load both games have moved on: "demo-9" is the user's turn
            // again (the opponent played), "demo-waiting" is still the opponent's (the user
            // played). Only the first is news.
            val client =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun myRunningGames(): List<GameSummary> {
                        refreshes++
                        val games = fake.myRunningGames()
                        if (refreshes == 1) return games
                        return games.map {
                            when (it.id.value) {
                                "demo-9", "demo-waiting" -> it.copy(moveNumber = it.moveNumber + 1)
                                else -> it
                            }
                        }
                    }
                }
            val session = sessionFor(client)
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))

            store.refresh()
            store.refresh()

            assertEquals(setOf(GameId(DEMO_SERVER, "demo-9")), store.arrivals.value.allRunning)
        }
}
