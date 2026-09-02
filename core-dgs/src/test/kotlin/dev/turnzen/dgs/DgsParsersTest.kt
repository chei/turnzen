package dev.turnzen.dgs

import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.PendingAction
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DgsParsersTest {
    @Test
    fun `login error lines map to exceptions`() {
        assertIs<GoServerException.InvalidCredentials>(
            LoginResponse.failure(Fixtures.text("login_error_wrong_userid.txt")),
        )
        assertIs<GoServerException.InvalidCredentials>(
            LoginResponse.failure(Fixtures.text("login_error_wrong_password.txt")),
        )
        assertNull(LoginResponse.failure(Fixtures.text("login_ok.txt")))
        assertIs<GoServerException.RateLimited>(LoginResponse.failure("[#Error: fever_vault; login.quota()]"))
    }

    @Test
    fun `time remaining formats`() {
        val jap = DgsTimeRemaining.parse("J: 83d 14h (+ 1d * 10)")!!
        assertEquals(Duration.ofDays(83).plusHours(14), jap.main)
        assertEquals("+ 1d * 10", jap.extra)

        val can = DgsTimeRemaining.parse("C: 6h (+ 15d / 8)")!!
        assertEquals(Duration.ofHours(6), can.main)

        val fis = DgsTimeRemaining.parse("F: 8d (+ 12h)")!!
        assertEquals(Duration.ofDays(8), fis.main)
        assertEquals("+ 12h", fis.extra)

        assertNull(DgsTimeRemaining.parse(""))
        assertNull(DgsTimeRemaining.parse(null))
    }

    @Test
    fun `dates are read as UTC`() {
        assertEquals(Instant.parse("2026-09-02T19:42:11Z"), DgsDates.parse("2026-09-02 19:42:11"))
        assertEquals(Instant.parse("2026-09-02T19:42:11Z"), DgsDates.parse("2026-09-02 19:42:11 GMT"))
        assertNull(DgsDates.parse(""))
        assertNull(DgsDates.parse("yesterday"))
    }

    @Test
    fun `user info`() {
        val user = UserInfoParser.parse(QuickSuite.parse(Fixtures.text("user_info.json")))
        assertEquals("123456", user.id)
        assertEquals("fakeuser", user.handle)
        assertEquals("Fake User", user.displayName)
        assertEquals("8k", user.rank)
    }

    @Test
    fun `status game list with both colors`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_status.json")), "123456")
        assertEquals(2, games.size)

        val first = games[0]
        assertEquals("1500001", first.id.value)
        assertEquals(StoneColor.BLACK, first.myColor)
        assertEquals("mkvtl", first.opponent.handle)
        assertEquals("7k", first.opponent.rank)
        assertEquals("M. K.", first.opponent.displayName)
        assertEquals(19, first.boardSize)
        assertEquals(0, first.handicap)
        assertEquals(87, first.moveNumber)
        assertEquals("87", first.moveToken.value)
        assertEquals(Duration.ofDays(3).plusHours(4), first.remainingTime?.main)
        assertEquals(Instant.parse("2026-09-02T19:42:11Z"), first.lastMoveAt)
        assertEquals(PendingAction.PLAY_MOVE, first.pendingAction)

        val second = games[1]
        assertEquals(StoneColor.WHITE, second.myColor)
        assertEquals("hana_go", second.opponent.handle)
        assertNull(second.opponent.displayName)
        assertEquals(13, second.boardSize)
        assertEquals(2, second.handicap)
        assertEquals(Duration.ofHours(6), second.remainingTime?.main)
    }

    @Test
    fun `own color falls back to move_color when the user id is unknown`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_status.json")), null)
        assertEquals(StoneColor.BLACK, games[0].myColor)
        assertEquals(StoneColor.WHITE, games[1].myColor)
    }

    @Test
    fun `game info`() {
        val summary = GameObjectParser.parse(QuickSuite.parse(Fixtures.text("game_info.json")), "123456")
        assertEquals("1000000", summary.id.value)
        assertEquals(5, summary.handicap)
        assertEquals("whitefake", summary.opponent.handle)
        assertEquals("264", summary.moveToken.value)
        assertEquals(Duration.ofDays(8), summary.remainingTime?.main)
    }

    @Test
    fun `real DGS SGF export parses`() {
        val game = SgfGameRecords.read(Fixtures.text("game_1000000.sgf"))
        val record = game.record
        assertEquals(19, record.boardSize)
        assertEquals(5, record.handicap)
        assertEquals(5, record.setupBlack.size)
        assertTrue(record.moves.size > 200, "expected a full game, got ${record.moves.size} moves")
        assertEquals("B+7.5", record.result)
        assertEquals(listOf("264"), game.rootProperties["XM"])
        assertNotNull(record.black.name)
        assertEquals(StoneColor.WHITE, record.initialPosition.toMove)
    }
}
