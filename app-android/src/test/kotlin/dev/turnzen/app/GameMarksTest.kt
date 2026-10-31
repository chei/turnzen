package dev.turnzen.app

import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.PrefsGameMarksStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.game.ResultReason
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.MessageTopic
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameMarksTest {
    private val demo19 = GameId(DEMO_SERVER, "demo-19")

    /** The demo server, with "demo-19" left off the running lists until [showAll] is set. */
    private class Hiding(
        val fake: FakeTurnBasedGoServerClient = FakeTurnBasedGoServerClient(latencyMillis = 0),
    ) : TurnBasedGoServerClient by fake {
        var showAll = false

        override suspend fun gamesAwaitingMe(): List<GameSummary> =
            fake.gamesAwaitingMe().filter {
                showAll ||
                    it.id.value != "demo-19"
            }

        override suspend fun myRunningGames(): List<GameSummary> =
            fake.myRunningGames().filter {
                showAll ||
                    it.id.value != "demo-19"
            }
    }

    @Test
    fun `a game that comes onto the list after the first load is marked until opened`() =
        runTest {
            val client = Hiding()
            val session = sessionFor(client)
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))

            // The first load marks nothing: every game would be new.
            store.refresh()
            assertTrue(
                store.marks.value.unopened
                    .isEmpty(),
            )

            client.showAll = true
            store.refresh()
            assertEquals(setOf(demo19), store.marks.value.unopened)

            // Still marked after another refresh: only opening it takes the mark off.
            store.refresh()
            assertEquals(setOf(demo19), store.marks.value.unopened)

            store.onOpened(demo19)
            assertTrue(
                store.marks.value.unopened
                    .isEmpty(),
            )
        }

    @Test
    fun `an invitation accepted on the demo server starts a game marked as begun`() =
        runTest {
            // The demo build's mailbox, the one with an invitation in it.
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0, shareGames = true)
            val session = sessionFor(fake)
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            val invitation = fake.newMessages().first { it.topic is MessageTopic.GameInvitation }
            fake.answerInvitation(invitation, accept = true, text = null)
            store.refresh()

            val begun =
                store.marks.value.unopened
                    .single()
            assertTrue(store.yourTurn.value!!.any { it.id == begun })
        }

    @Test
    fun `a game that ends on the server stays on the list once, as the archive has it`() =
        runTest {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val session = sessionFor(fake)
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            // Resigned on the website, say: the app hears of it only from the lists.
            fake.resign(demo19, store.summary(demo19)!!.moveToken, null)
            store.refresh()

            val ended = store.marks.value.ended
            assertEquals(listOf(demo19), ended.map { it.id })
            assertTrue(ended.single().isFinished)
            assertEquals(ResultReason.RESIGNATION, ended.single().result?.by)
            // Off the running lists themselves, so the tab counts and the queue skip it.
            assertTrue(store.yourTurn.value!!.none { it.id == demo19 })
            assertTrue(store.allRunning.value!!.none { it.id == demo19 })
            // The board can still open it from the list.
            assertNotNull(store.summary(demo19))

            store.refresh()
            assertEquals(
                listOf(demo19),
                store.marks.value.ended
                    .map { it.id },
            )

            store.onOpened(demo19)
            assertTrue(
                store.marks.value.ended
                    .isEmpty(),
            )
        }

    @Test
    fun `a game that leaves the list but is not in the archive is let go`() =
        runTest {
            val client = Hiding().apply { showAll = true }
            val session = sessionFor(client)
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            client.showAll = false
            store.refresh()
            assertTrue(
                store.marks.value.ended
                    .isEmpty(),
            )
        }

    @Test
    fun `a game the user resigned in the app is not marked as ended`() =
        runTest {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val session = sessionFor(fake)
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()

            fake.resign(demo19, store.summary(demo19)!!.moveToken, null)
            store.onGameEnded(demo19)
            store.refresh()
            assertTrue(
                store.marks.value.ended
                    .isEmpty(),
            )
        }

    @Test
    fun `the marks outlive the app`() =
        runTest {
            val prefs = FakeSharedPreferences()
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val session = sessionFor(fake)
            session.signIn(Credentials("demo", "pw"))
            val before = GamesStore(Accounts(listOf(session)), marksStore = PrefsGameMarksStore(prefs))
            before.refresh()
            fake.resign(demo19, before.summary(demo19)!!.moveToken, null)
            before.refresh()

            val after = GamesStore(Accounts(listOf(session)), marksStore = PrefsGameMarksStore(prefs))
            // Result and rating change included: the row shows them.
            assertNotNull(
                after.marks.value.ended
                    .single()
                    .result,
            )
            assertEquals(before.marks.value, after.marks.value)
        }
}
