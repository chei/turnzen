package dev.turnzen.app

import dev.turnzen.app.notify.WatchState
import dev.turnzen.app.notify.WatchStateStore
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStateStoreTest {
    @Test
    fun `the state survives a restart`() {
        val prefs = FakeSharedPreferences()
        val a = GameId(ServerId("dgs"), "1000001")
        val b = GameId(ServerId("ogs"), "42")
        val state =
            WatchState(
                known = mapOf(a to 12, b to 3),
                flagged = setOf(b),
                lowAlerted = mapOf(a to 12),
                signedOutNoticed = setOf(ServerId("ogs")),
            )
        WatchStateStore(prefs).update { state }
        assertEquals(state, WatchStateStore(prefs).read())
    }

    @Test
    fun `a first run starts empty`() {
        assertEquals(WatchState(), WatchStateStore(FakeSharedPreferences()).read())
    }
}
