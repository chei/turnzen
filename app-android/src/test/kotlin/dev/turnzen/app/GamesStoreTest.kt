package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GamesStoreTest {
    @Test
    fun `games are sorted by most recent move first and advance in order`() =
        runTest {
            val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0))
            session.signIn(Credentials("demo", "pw"))
            val store = GamesStore(session)
            val games = store.refresh()
            assertEquals(listOf("demo-19", "demo-13", "demo-9"), games.map { it.id.value })

            assertEquals(GameId("demo-13"), store.nextAfter(GameId("demo-19")))
            store.remove(GameId("demo-19"))
            assertEquals(GameId("demo-9"), store.nextAfter(GameId("demo-13")))
            store.remove(GameId("demo-13"))
            store.remove(GameId("demo-9"))
            assertNull(store.nextAfter(GameId("demo-9")))
        }
}
