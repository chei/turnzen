package dev.turnzen.dgs

import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.PendingAction
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * The recorded fixtures in [DgsParsersTest] cover what DGS sends on a good day. These cover
 * the other half: fields missing, fields the client does not recognise, and the fallbacks
 * between them. They are hand-built rather than recorded because there is no real response
 * to record — the point is what happens when DGS sends something this client did not expect.
 */
class GameObjectParserTest {
    private val me = "123456"

    /** The smallest game object the parser accepts, as a starting point to break. */
    private val minimal =
        linkedMapOf(
            "id" to "1500001",
            "size" to "19",
            "move_id" to "87",
            "move_color" to "\"B\"",
        )

    /** [minimal] with fields replaced, or removed when the value is null. */
    private fun game(vararg changes: Pair<String, String?>): JsonObject {
        val fields = LinkedHashMap(minimal)
        for ((key, value) in changes) {
            if (value == null) fields.remove(key) else fields[key] = value
        }
        return QuickSuite.extractJson(fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" })
    }

    private fun parse(vararg changes: Pair<String, String?>) = GameObjectParser.parse(game(*changes), me)

    private fun protocolFailure(vararg changes: Pair<String, String?>): String =
        assertFailsWith<GoServerException.Protocol> { parse(*changes) }.message.orEmpty()

    @Test
    fun `a game with no id is rejected rather than given an empty one`() {
        assertTrue(protocolFailure("id" to null).contains("id"))
    }

    @Test
    fun `a game with no board size is rejected, naming the game`() {
        // Defaulting to 19 here would silently draw the wrong board for a 9x9 game.
        val message = protocolFailure("size" to null)
        assertTrue(message.contains("size"), message)
        assertTrue(message.contains("1500001"), message)
    }

    @Test
    fun `a game with no move_id is rejected`() {
        // The move token is what a Send is addressed to; a game without one cannot be played.
        assertTrue(protocolFailure("move_id" to null).contains("move_id"))
    }

    @Test
    fun `a game that says nothing about colors is rejected`() {
        val message = protocolFailure("move_color" to null)
        assertTrue(message.contains("color"), message)
        assertTrue(message.contains("1500001"), message)
    }

    @Test
    fun `a move color the client does not know counts as no color at all`() {
        assertFailsWith<GoServerException.Protocol> { parse("move_color" to "\"purple\"") }
    }

    @Test
    fun `a move color is read whatever its case and spacing`() {
        assertEquals(StoneColor.BLACK, parse("move_color" to "\" b \"").myColor)
        assertEquals(StoneColor.WHITE, parse("move_color" to "\"w\"").myColor)
    }

    @Test
    fun `the player ids decide the color, not the move color`() {
        // It is white's turn, but I am black: my color must not follow whose turn it is.
        val summary =
            parse(
                "black_user" to """{"id":123456,"handle":"fakeuser"}""",
                "white_user" to """{"id":222,"handle":"opp"}""",
                "move_color" to "\"W\"",
            )
        assertEquals(StoneColor.BLACK, summary.myColor)
        assertEquals("opp", summary.opponent.handle)
        assertFalse(summary.isMyTurn)
    }

    @Test
    fun `an opponent the server did not describe is shown as a placeholder`() {
        val opponent = parse().opponent
        assertEquals("?", opponent.handle)
        assertNull(opponent.displayName)
        assertNull(opponent.rank)
    }

    @Test
    fun `an opponent with a blank name falls back to the handle alone`() {
        val opponent =
            parse(
                "black_user" to """{"id":123456,"handle":"fakeuser"}""",
                "white_user" to """{"id":222,"handle":"opp","name":"   ","rating":""}""",
            ).opponent
        assertEquals("opp", opponent.handle)
        assertNull(opponent.displayName, "a blank name is not a name")
        assertNull(opponent.rank)
    }

    @Test
    fun `the remaining time comes from my own side of the game, not the opponent's`() {
        // Reading the wrong gameinfo would show the opponent's clock as the user's own.
        val summary =
            parse(
                "black_user" to """{"id":123456,"handle":"fakeuser"}""",
                "white_user" to """{"id":222,"handle":"opp"}""",
                "black_gameinfo" to """{"remtime":"F: 2d (+ 12h)"}""",
                "white_gameinfo" to """{"remtime":"F: 40d (+ 12h)"}""",
            )
        assertEquals(StoneColor.BLACK, summary.myColor)
        assertEquals(2.days, summary.remainingTime?.main)
    }

    @Test
    fun `a game with no clock information simply has none`() {
        assertNull(parse().remainingTime)
        assertNull(parse().lastMoveAt)
    }

    @Test
    fun `a handicap that is not mentioned is no handicap`() {
        assertEquals(0, parse().handicap)
        assertEquals(9, parse("handicap" to "9").handicap)
    }

    @Test
    fun `the move number prefers the count but accepts the move id`() {
        assertEquals(87, parse().moveNumber, "move_id stands in when there is no count")
        assertEquals(90, parse("move_count" to "90").moveNumber)
        assertEquals(0, parse("move_id" to "\"x\"", "move_count" to null).moveNumber)
    }

    @Test
    fun `whose turn it is follows move_uid when the server gives one`() {
        assertTrue(parse("move_uid" to "123456").isMyTurn)
        assertFalse(parse("move_uid" to "777").isMyTurn)
    }

    @Test
    fun `whose turn it is falls back to the move color`() {
        val mine = parse("black_user" to """{"id":123456,"handle":"fakeuser"}""", "move_color" to "\"B\"")
        assertTrue(mine.isMyTurn)
        val theirs = parse("black_user" to """{"id":123456,"handle":"fakeuser"}""", "move_color" to "\"W\"")
        assertFalse(theirs.isMyTurn, "it is white to play and I am black")
    }

    @Test
    fun `the game action code says what the server is waiting for`() {
        assertEquals(PendingAction.PLACE_HANDICAP, parse("game_action" to "1").pendingAction)
        assertEquals(PendingAction.PLAY_MOVE, parse("game_action" to "2").pendingAction)
        assertEquals(PendingAction.SCORE, parse("game_action" to "3").pendingAction)
        for (komi in 10..13) {
            assertEquals(PendingAction.NEGOTIATE_KOMI, parse("game_action" to "$komi").pendingAction)
        }
    }

    @Test
    fun `an unknown action code falls back to the game status`() {
        assertEquals(PendingAction.PLAY_MOVE, parse("game_action" to "99", "status" to "\"PLAY\"").pendingAction)
        assertEquals(PendingAction.PLAY_MOVE, parse("game_action" to null, "status" to "\"pass\"").pendingAction)
        assertEquals(PendingAction.SCORE, parse("status" to "\"SCORE\"").pendingAction)
        assertEquals(PendingAction.SCORE, parse("status" to "\"SCORE2\"").pendingAction)
        assertEquals(PendingAction.NEGOTIATE_KOMI, parse("status" to "\"KOMI\"").pendingAction)
    }

    @Test
    fun `a status nobody recognises is not mistaken for a move to play`() {
        assertEquals(PendingAction.OTHER, parse("status" to "\"FINISHED\"").pendingAction)
        assertEquals(PendingAction.OTHER, parse().pendingAction)
    }

    @Test
    fun `a list with no result section is empty rather than an error`() {
        // DGS answers this way when the user has no games at all.
        assertEquals(emptyList(), GameObjectParser.parseList(QuickSuite.extractJson("""{"error":""}"""), me))
    }

    @Test
    fun `a list of plain strings names the option that would have fixed it`() {
        // Without lstyle=json DGS returns rows, not objects; the message has to say so.
        val body = """{"list_result":["1500001|19|B"]}"""
        val message =
            assertFailsWith<GoServerException.Protocol> {
                GameObjectParser.parseList(QuickSuite.extractJson(body), me)
            }.message.orEmpty()
        assertTrue(message.contains("lstyle=json"), message)
    }

    @Test
    fun `my_id stands in when the caller does not know who it is`() {
        val body = game("my_id" to "123456", "black_user" to """{"id":123456,"handle":"fakeuser"}""")
        assertEquals(StoneColor.BLACK, GameObjectParser.parse(body, null).myColor)
    }
}
