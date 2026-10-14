package dev.turnzen.ogs

import dev.turnzen.game.Point
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.PendingAction
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A 9x9 game in the stone removal phase, written from the protocol's description rather than
 * recorded: no game on the test account has reached the phase yet.
 *
 * Black walls off columns A–D with a stone on every point of column E, white walls off G–J
 * with column F. Each then played one stone into the other's area — white at B8, black at
 * H2 — and both passed. Neither stone can live.
 */
internal object StoneRemovalGame {
    const val ID = 7000005L
    const val MOVES = 22

    fun gamedata(
        removed: String = "",
        blackAccepted: String? = null,
        whiteAccepted: String? = null,
        phase: String = "stone removal",
    ): String {
        val moves =
            (0 until 9).joinToString(",") { row -> "[4,$row,1000],[5,$row,1000]" } +
                ",[7,7,1000],[1,1,1000],[-1,-1,1000],[-1,-1,1000]"

        fun player(
            id: Long,
            name: String,
            accepted: String?,
        ) = """{"id":$id,"username":"$name"""" + (accepted?.let { ""","accepted_stones":"$it"""" } ?: "") + "}"
        return """{"game_id":$ID,"phase":"$phase","width":9,"height":9,"komi":6.5,"rules":"japanese",""" +
            """"handicap":0,"moves":[$moves],"removed":"$removed","black_player_id":1001,"white_player_id":2007,""" +
            """"players":{"black":${player(1001, "turnzen-test", blackAccepted)},""" +
            """"white":${player(2007, "opponent7", whiteAccepted)}},""" +
            """"time_control":{"system":"fischer","speed":"correspondence"},""" +
            """"clock":{"current_player":2007,"last_move":1790500000000}}"""
    }

    fun obj(
        removed: String = "",
        blackAccepted: String? = null,
        whiteAccepted: String? = null,
    ): JsonObject = OgsJson.parseObject(gamedata(removed, blackAccepted, whiteAccepted), "game data")
}

class OgsStoneRemovalTest {
    private val now = Instant.fromEpochMilliseconds(1790500000000)

    private fun summary(gamedata: JsonObject) = OgsGameData.summary(gamedata, null, null, me = 1001, now = now)

    @Test
    fun `the marks are read as points, dame included, and written back row by row`() {
        val marks = OgsStoneRemoval.read(StoneRemovalGame.obj(removed = "hhbbaa"))
        assertEquals(setOf(Point(7, 7), Point(1, 1), Point(0, 0)), marks.marked)
        // A1's corner point holds no stone: a dame mark, not a dead stone.
        assertEquals(setOf(Point(7, 7), Point(1, 1)), marks.dead)
        assertEquals("aabbhh", marks.encoded)
    }

    @Test
    fun `newer game data writes the marks as a list of points`() {
        val element = OgsJson.json.parseToJsonElement("""[{"x":1,"y":1},{"x":7,"y":7},{"x":12,"y":0}]""")
        assertEquals(setOf(Point(1, 1), Point(7, 7)), OgsStoneRemoval.points(element, 9))
    }

    @Test
    fun `the count is this device's, over the server's marks`() {
        val state = OgsStoneRemoval.state(OgsStoneRemoval.read(StoneRemovalGame.obj(removed = "bbhh")))
        assertEquals(setOf(Point(1, 1), Point(7, 7)), state.deadStones)
        assertEquals(36, state.blackTerritory.size)
        assertEquals(27, state.whiteTerritory.size)
        // Black 36 + 1 prisoner against white 27 + 1 + 6.5 komi.
        assertEquals(StoneColor.BLACK, state.result?.winner)
        assertEquals(2.5, state.result?.margin)
    }

    @Test
    fun `taps preview a change on the server's marks`() {
        val marks = OgsStoneRemoval.read(StoneRemovalGame.obj(removed = "bb"))
        val state = OgsStoneRemoval.state(marks, toggle = listOf(Point(7, 7)))
        assertEquals(setOf(Point(1, 1), Point(7, 7)), state.deadStones)
    }

    @Test
    fun `a game that has left the phase has moved on`() {
        val finished = OgsJson.parseObject(StoneRemovalGame.gamedata(phase = "finished"), "game data")
        assertFailsWith<GoServerException.StaleGame> { OgsStoneRemoval.read(finished) }
    }

    @Test
    fun `the phase waits on the user until they accept the marks as they stand`() {
        val unaccepted = summary(StoneRemovalGame.obj(removed = "bbhh"))
        assertEquals(PendingAction.SCORE, unaccepted.pendingAction)
        assertTrue(unaccepted.isMyTurn)

        // The same marks, in another order: accepted.
        assertFalse(summary(StoneRemovalGame.obj(removed = "hhbb", blackAccepted = "bbhh")).isMyTurn)

        // Marks changed after the user accepted: their acceptance no longer counts.
        assertTrue(summary(StoneRemovalGame.obj(removed = "bb", blackAccepted = "bbhh")).isMyTurn)

        // Only the opponent's acceptance is there: still the user's to do.
        assertTrue(summary(StoneRemovalGame.obj(removed = "bbhh", whiteAccepted = "bbhh")).isMyTurn)
    }
}
