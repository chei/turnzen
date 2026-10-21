package dev.turnzen.app

import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.CachedLists
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.PrefsGamesCache
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class GamesCacheTest {
    private val fetchedAt = Instant.fromEpochMilliseconds(1_790_500_000_000)

    @Test
    fun `the running lists survive a restart, each clock run down by the time since`() =
        runTest {
            val prefs = FakeSharedPreferences()
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val before = GamesStore(Accounts(listOf(session)), cache = PrefsGamesCache(prefs), now = { fetchedAt })
            before.refresh()

            val after =
                GamesStore(Accounts(listOf(session)), cache = PrefsGamesCache(prefs), now = {
                    fetchedAt +
                        2.hours
                })
            val was = assertNotNull(before.allRunning.value)
            val now = assertNotNull(after.allRunning.value)
            assertEquals(was.map { it.id }, now.map { it.id })
            assertEquals(before.yourTurn.value?.map { it.id }, after.yourTurn.value?.map { it.id })
            assertEquals(mapOf(session.serverId to fetchedAt), after.listedAt.value)

            val game = was.first { it.remainingTime?.main != null }
            val aged = now.first { it.id == game.id }
            assertEquals(game.remainingTime!!.main!! - 2.hours, aged.remainingTime!!.main)
            assertEquals(game.copy(remainingTime = aged.remainingTime), aged)
        }

    @Test
    fun `signing out of a server forgets its stored games`() =
        runTest {
            val prefs = FakeSharedPreferences()
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(Accounts(listOf(session)), cache = PrefsGamesCache(prefs))
            store.refresh()
            store.signOut(session)

            assertTrue(PrefsGamesCache(prefs).load().isEmpty())
            assertNull(GamesStore(Accounts(listOf(session)), cache = PrefsGamesCache(prefs)).allRunning.value)
        }

    @Test
    fun `an entry that does not read back is a miss`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("dgs", "{not json").apply()
        prefs.edit().putString("ogs", """{"v":99}""").apply()
        assertTrue(PrefsGamesCache(prefs).load().isEmpty())
    }

    @Test
    fun `a summary reads back as it was written`() =
        runTest {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            fake.signIn(Credentials("demo", "pw"))
            val games = fake.myRunningGames()
            val prefs = FakeSharedPreferences()
            val server = ServerId("dgs")
            PrefsGamesCache(prefs).save(server, CachedLists(fetchedAt, games.take(1), games))
            val back = assertNotNull(PrefsGamesCache(prefs).load()[server])
            assertEquals(CachedLists(fetchedAt, games.take(1), games), back)
        }
}
