package dev.turnzen.app

import dev.turnzen.app.data.LadderStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.TournamentRef
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the cache is for: the standings page costs ~54 KB, so the question that matters is how
 * many times a week it is fetched, not whether one fetch works.
 */
class LadderStoreTest {
    private val ladder = TournamentRef(id = "3", isLadder = true)
    private val roundRobin = TournamentRef(id = "365", isLadder = false)
    private val mine = GameId("1503981")
    private val alsoMine = GameId("1501653")

    private val answer =
        mapOf(
            mine to LadderStanding(153, 108, youAreChallenger = true),
            alsoMine to LadderStanding(153, 149, youAreChallenger = false),
        )

    /** Counts fetches, which is the whole point of the thing under test. */
    private class CountingClient(
        private val answer: Map<GameId, LadderStanding>,
        private val fail: GoServerException? = null,
    ) : TurnBasedGoServerClient by FakeTurnBasedGoServerClient(latencyMillis = 0) {
        var fetches = 0

        override suspend fun ladderStandings(tournament: TournamentRef): Map<GameId, LadderStanding> {
            fetches++
            fail?.let { throw it }
            return answer
        }
    }

    private class Clock(
        var millis: Long = 1_000_000,
    ) {
        fun advanceDays(days: Long) {
            millis += days * 24 * 60 * 60 * 1000
        }

        fun advanceMinutes(minutes: Long) {
            millis += minutes * 60 * 1000
        }
    }

    private fun storeFor(
        client: CountingClient,
        clock: Clock,
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
    ): LadderStore {
        val session = sessionFor(client, InMemoryCredentialStore())
        return LadderStore(prefs, session, ttlMillis = 24L * 60 * 60 * 1000) { clock.millis }
    }

    @Test
    fun `every game on one ladder is answered by a single fetch`() =
        runTest {
            val client = CountingClient(answer)
            val clock = Clock()
            val session = sessionFor(client, InMemoryCredentialStore())
            session.signIn(Credentials("demo", "pw"))
            val store = LadderStore(FakeSharedPreferences(), session, 24L * 60 * 60 * 1000) { clock.millis }

            assertEquals(108, store.standing(ladder, mine)?.opponentRank)
            assertEquals(149, store.standing(ladder, alsoMine)?.opponentRank)
            repeat(10) { store.standing(ladder, mine) }

            assertEquals(1, client.fetches)
        }

    @Test
    fun `a week of daily openings costs one fetch a day, not one per opening`() =
        runTest {
            // Four running games, the sheet opened on each every day for a week. The cache is
            // written through the preferences, so this also covers surviving a process death:
            // nothing is kept in the store's own fields between the reads below.
            val client = CountingClient(answer)
            val clock = Clock()
            val prefs = FakeSharedPreferences()
            val session = sessionFor(client, InMemoryCredentialStore())
            session.signIn(Credentials("demo", "pw"))

            repeat(7) {
                repeat(4) {
                    // A brand new store each time: the process was reclaimed in between.
                    LadderStore(prefs, session, 24L * 60 * 60 * 1000) { clock.millis }
                        .standing(ladder, mine)
                }
                clock.advanceDays(1)
            }

            assertEquals(7, client.fetches)
        }

    @Test
    fun `a stale entry is refetched`() =
        runTest {
            val client = CountingClient(answer)
            val clock = Clock()
            val session = sessionFor(client, InMemoryCredentialStore())
            session.signIn(Credentials("demo", "pw"))
            val prefs = FakeSharedPreferences()
            val store = LadderStore(prefs, session, 24L * 60 * 60 * 1000) { clock.millis }

            store.standing(ladder, mine)
            clock.advanceDays(2)
            store.standing(ladder, mine)

            assertEquals(2, client.fetches)
        }

    @Test
    fun `a game the entry has never heard of refetches, but not on every opening`() =
        runTest {
            // A challenge that started after the entry was written is a real reason to look
            // again. A game that is genuinely absent must not refetch the page every time.
            val client = CountingClient(answer)
            val clock = Clock()
            val session = sessionFor(client, InMemoryCredentialStore())
            session.signIn(Credentials("demo", "pw"))
            val store = LadderStore(FakeSharedPreferences(), session, 24L * 60 * 60 * 1000) { clock.millis }
            val unknown = GameId("9999999")

            assertNull(store.standing(ladder, unknown))
            repeat(5) { store.standing(ladder, unknown) }
            assertEquals(1, client.fetches)

            clock.advanceMinutes(11)
            assertNull(store.standing(ladder, unknown))
            assertEquals(2, client.fetches)
        }

    @Test
    fun `ending a game forgets the ladder`() =
        runTest {
            val client = CountingClient(answer)
            val clock = Clock()
            val session = sessionFor(client, InMemoryCredentialStore())
            session.signIn(Credentials("demo", "pw"))
            val prefs = FakeSharedPreferences()
            val store = LadderStore(prefs, session, 24L * 60 * 60 * 1000) { clock.millis }

            store.standing(ladder, mine)
            store.invalidate(ladder)
            store.standing(ladder, mine)

            assertEquals(2, client.fetches)
        }

    @Test
    fun `a tournament that is not a ladder is never fetched`() =
        runTest {
            val client = CountingClient(answer)
            val store = storeFor(client, Clock())

            assertNull(store.standing(roundRobin, mine))
            assertNull(store.standing(null, mine))

            assertEquals(0, client.fetches)
        }

    @Test
    fun `a failure is silent and is not cached as an answer`() =
        runTest {
            val client = CountingClient(answer, fail = GoServerException.Unreachable("offline"))
            val clock = Clock()
            val session = sessionFor(client, InMemoryCredentialStore())
            session.signIn(Credentials("demo", "pw"))
            val store = LadderStore(FakeSharedPreferences(), session, 24L * 60 * 60 * 1000) { clock.millis }

            assertNull(store.standing(ladder, mine))
            // Nothing was written, so the next opening tries again rather than showing nothing
            // for a day because the phone was briefly in a tunnel.
            assertNull(store.standing(ladder, mine))
            assertEquals(2, client.fetches)
        }
}
