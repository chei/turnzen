package dev.turnzen.ogs

import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.ResultReason
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.PendingAction
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class OgsParsersTest {
    private fun gamedata(name: String): JsonObject = Fixtures.obj(name).obj("gamedata")!!

    @Test
    fun `ranks follow the website's formula and say so when provisional`() {
        assertEquals("6k", OgsRanks.label(OgsRanks.ranking(1500.0), provisional = false))
        assertEquals("1d", OgsRanks.label(30.2, provisional = false))
        assertEquals("30k", OgsRanks.label(0.5, provisional = false))
        assertEquals("?", OgsRanks.label(24.3, provisional = true))
    }

    @Test
    fun `a running game on the opponent's turn`() {
        val game = Fixtures.obj("game_running_paused.json")
        val summary =
            OgsGameData.summary(
                game.obj("gamedata")!!,
                game.obj("players")!!.obj("black"),
                game.obj("players")!!.obj("white"),
                Fixtures.ME,
                Instant.fromEpochMilliseconds(1790500000000),
            )
        assertEquals("7000001", summary.id.value)
        assertEquals(OgsClient.SERVER_ID, summary.id.server)
        assertEquals(StoneColor.WHITE, summary.myColor)
        assertEquals("opponent8", summary.opponent.handle)
        assertEquals("?", summary.opponent.rank)
        // An uploaded picture, as sent.
        assertEquals("https://avatars.example.invalid/2008-0f1e2d3c4b5a6978-32.png", summary.opponent.iconUrl)
        assertEquals(19, summary.boardSize)
        assertEquals(72, summary.moveNumber)
        assertEquals(MoveTokenValue("72"), MoveTokenValue(summary.moveToken.value))
        assertEquals(PendingAction.PLAY_MOVE, summary.pendingAction)
        assertFalse(summary.isMyTurn)
        assertFalse(summary.onlyOnWeb)
        assertEquals(Instant.fromEpochMilliseconds(1790405991398), summary.lastMoveAt)
        // Not the user's move: their clock is not running, the full seven days are left.
        assertEquals(7.days, summary.remainingTime?.main)
        assertEquals("+ 1d", summary.remainingTime?.extra)
    }

    @Test
    fun `a clock stopped by a vacation counts only the time before the pause`() {
        val data = gamedata("game_first_move.json")
        val clock = data.obj("clock")
        // The opponent is to move, and their clock ran from the last move until the pause:
        // 46 h 11 m of their three days.
        val far = Instant.fromEpochMilliseconds(1790900000000)
        val remaining = OgsClock.remaining(clock, data.obj("time_control"), 2007, far, OgsGameData.isPaused(data))
        assertEquals((259_200_000L - (1789825722323 - 1789659406179)).milliseconds, remaining?.main)
    }

    @Test
    fun `a running clock counts down from the last move`() {
        val data = gamedata("game_first_move.json")
        val lastMove = 1789659406179
        val remaining =
            OgsClock.remaining(
                data.obj("clock"),
                data.obj("time_control"),
                2007,
                Instant.fromEpochMilliseconds(lastMove + 30.hours.inWholeMilliseconds),
                paused = false,
            )
        assertEquals(42.hours, remaining?.main)
    }

    @Test
    fun `byo-yomi overrun eats whole periods`() {
        val clock =
            OgsJson.parseObject(
                """{"current_player":1,"black_player_id":1,"white_player_id":2,"last_move":0,
                   "black_time":{"thinking_time":3600,"periods":5,"period_time":86400}}""",
                "clock",
            )
        val tc = OgsJson.parseObject("""{"system":"byoyomi","period_time":86400,"periods":5}""", "tc")
        val remaining =
            OgsClock.remaining(
                clock,
                tc,
                1,
                Instant.fromEpochMilliseconds(2.days.inWholeMilliseconds),
                paused = false,
            )
        assertEquals(Duration.ZERO, remaining?.main)
        assertEquals("+ 4 × 1d", remaining?.extra)
    }

    @Test
    fun `the record replays the moves in turn`() {
        val record = OgsGameData.record(gamedata("game_running_paused.json"), "b", "w")
        assertEquals(19, record.boardSize)
        assertEquals(72, record.moves.size)
        assertEquals(StoneColor.BLACK, record.moves[0].color)
        assertEquals(Move.Play(Point(15, 3)), record.moves[0].move)
        assertEquals(StoneColor.WHITE, record.moves[1].color)
        assertEquals(6.5, record.komi)
    }

    @Test
    fun `handicap stones placed as moves are all black's`() {
        val data =
            OgsJson.parseObject(
                """{"game_id":1,"width":9,"height":9,"handicap":2,"initial_player":"black",
                   "moves":[[2,2,0],[6,6,0],[4,4,0],[-1,-1,0]]}""",
                "gd",
            )
        val record = OgsGameData.record(data, null, null)
        assertEquals(
            listOf(StoneColor.BLACK, StoneColor.BLACK, StoneColor.WHITE, StoneColor.BLACK),
            record.moves.map { it.color },
        )
        assertEquals(Move.Pass, record.moves[3].move)
    }

    @Test
    fun `game info carries the rules and the time system`() {
        val game = Fixtures.obj("game_running_paused.json")
        val info = OgsGameData.info(game.obj("gamedata")!!, null, null, Instant.fromEpochMilliseconds(1790500000000))
        assertEquals("Japanese", info.ruleset)
        assertEquals(ScoringRules.TERRITORY, info.scoring)
        assertEquals("Fischer: 3d + 1d, up to 7d", info.timeLimit)
        assertEquals(true, info.weekendClock)
        assertEquals("turnzen-test", info.white.handle)
    }

    @Test
    fun `outcomes read as results`() {
        assertEquals(ResultReason.TIMEOUT, OgsOutcome.result("Timeout", blackLost = true, whiteLost = false)?.by)
        assertEquals(StoneColor.WHITE, OgsOutcome.result("Timeout", blackLost = true, whiteLost = false)?.winner)
        val points = OgsOutcome.result("11.5 points", blackLost = false, whiteLost = true)
        assertEquals(StoneColor.BLACK, points?.winner)
        assertEquals(11.5, points?.margin)
        assertEquals(ResultReason.NO_RESULT, OgsOutcome.result("Cancellation", blackLost = true, whiteLost = true)?.by)
        assertNull(OgsOutcome.result(null, null, null))
    }

    @Test
    fun `finished list keeps correspondence games only`() {
        val results = Fixtures.obj("player_games_finished.json").arr("results")!!.map { it as JsonObject }
        val finished = results.filter { it.str("ended") != null }
        val kept = finished.filter(OgsFinishedGames::isCorrespondence)
        // Four blitz games were played that week; they are live play, which Turnzen ignores.
        assertEquals(listOf("7000010", "7000002"), kept.map { it.long("id").toString() }.sortedDescending())
        val won = OgsFinishedGames.summary(kept.first { it.long("id") == 7000002L }, Fixtures.ME, ratingChange = null)!!
        assertEquals(ResultReason.TIMEOUT, won.result?.by)
        assertEquals(StoneColor.WHITE, won.result?.winner)
        assertEquals(StoneColor.WHITE, won.myColor)
        assertTrue(won.isFinished)
        assertEquals("opponent9", won.opponent.handle)
        // A Gravatar, asked for at a size a list row can show sharply.
        assertEquals(
            "https://secure.gravatar.com/avatar/000000000000000000000000000007d9?s=128&d=retro",
            won.opponent.iconUrl,
        )
    }

    @Test
    fun `an icon is read only when it is an https address`() {
        assertNull(OgsIcons.url(null))
        assertNull(OgsIcons.url(OgsJson.parseObject("""{"username": "a"}""", "test")))
        assertNull(OgsIcons.url(OgsJson.parseObject("""{"icon": "http://example.invalid/1-32.png"}""", "test")))
        assertEquals(
            "https://secure.gravatar.com/avatar/ab?d=retro&s=128",
            OgsIcons.url(
                OgsJson.parseObject("""{"icon": "https://secure.gravatar.com/avatar/ab?d=retro&s=32"}""", "test"),
            ),
        )
    }

    @Test
    fun `rating history gives each game's change in ranks`() {
        val tsv =
            "ended\tgame_id\tplayed_black\thandicap\trating\tdeviation\n" +
                "300\t12\t1\t0\t1450.00\t200\n" +
                "200\t11\t0\t0\t1550.00\t220\n" +
                "100\t0\t0\t0\t1500.00\t250\n"
        val changes = OgsRatingHistory.changes(tsv)
        assertEquals(setOf("11", "12"), changes.keys)
        assertTrue(changes.getValue("11") > 0.7 && changes.getValue("11") < 0.8)
        assertTrue(changes.getValue("12") < 0)
    }

    private data class MoveTokenValue(
        val value: String,
    )
}
