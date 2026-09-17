package dev.turnzen.dgs

import dev.turnzen.game.GameResult
import dev.turnzen.game.ResultReason
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfCoordinates
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.CredentialProblem
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.TournamentRef
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DgsParsersTest {
    // The fixture-driven assertions below expect UTC instants, and the parser now reads
    // bare DGS times in the JVM's default zone. Pin it to UTC so the tests are the same on
    // any machine; the zone-conversion behaviour itself is covered explicitly further down.
    private val savedZone = TimeZone.getDefault()

    @BeforeTest
    fun pinUtc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @AfterTest
    fun restoreZone() = TimeZone.setDefault(savedZone)

    @Test
    fun `login error lines map to exceptions`() {
        val unknownUser = LoginResponse.failure(Fixtures.text("login_error_wrong_userid.txt"))
        assertIs<GoServerException.InvalidCredentials>(unknownUser)
        assertEquals(CredentialProblem.UNKNOWN_USER, unknownUser.problem)

        val wrongPassword = LoginResponse.failure(Fixtures.text("login_error_wrong_password.txt"))
        assertIs<GoServerException.InvalidCredentials>(wrongPassword)
        assertEquals(CredentialProblem.WRONG_PASSWORD, wrongPassword.problem)
        assertNull(LoginResponse.failure(Fixtures.text("login_ok.txt")))
        assertIs<GoServerException.RateLimited>(LoginResponse.failure("[#Error: fever_vault; login.quota()]"))
    }

    @Test
    fun `time remaining keeps the server wording and parses English units`() {
        val jap = DgsTimeRemaining.parse("J: 83d 14h (+ 1d * 10)")!!
        assertEquals("83d 14h", jap.text)
        assertEquals(Duration.ofDays(83).plusHours(14), jap.main)
        assertEquals("+ 1d * 10", jap.extra)

        val can = DgsTimeRemaining.parse("C: 6h (+ 15d / 8)")!!
        assertEquals("6h", can.text)
        assertEquals(Duration.ofHours(6), can.main)

        val fis = DgsTimeRemaining.parse("F: 8d (+ 12h)")!!
        assertEquals("8d", fis.text)
        assertEquals(Duration.ofDays(8), fis.main)
        assertEquals("+ 12h", fis.extra)

        assertNull(DgsTimeRemaining.parse(""))
        assertNull(DgsTimeRemaining.parse(null))
    }

    @Test
    fun `time remaining understands a translated account`() {
        // DGS renders this field in the account's language: "nap" and "óra" for Hungarian.
        val hu = DgsTimeRemaining.parse("F: 3n 20ó (+ 1n 6ó)")!!
        assertEquals("3n 20ó", hu.text)
        assertEquals(Duration.ofDays(3).plusHours(20), hu.main)
        assertEquals("+ 1n 6ó", hu.extra)

        val huDaysOnly = DgsTimeRemaining.parse("F: 10n (+ 1n 6ó)")!!
        assertEquals(Duration.ofDays(10), huDaysOnly.main)
    }

    @Test
    fun `an unknown language still displays, but claims no duration`() {
        val unknown = DgsTimeRemaining.parse("F: 3xy 20zz (+ 1xy)")!!
        assertEquals("3xy 20zz", unknown.text)
        assertNull(unknown.main)
        assertEquals("+ 1xy", unknown.extra)
    }

    @Test
    fun `a bare date is read in the given zone, a GMT date stays UTC`() {
        val budapest = ZoneId.of("Europe/Budapest") // CEST (+2) on this date
        // The bare DGS wall-clock is the user's local time: 19:42 in Budapest is 17:42 UTC.
        assertEquals(Instant.parse("2026-09-02T17:42:11Z"), DgsDates.parse("2026-09-02 19:42:11", budapest))
        // A trailing GMT means the value really is UTC, whatever the device zone.
        assertEquals(Instant.parse("2026-09-02T19:42:11Z"), DgsDates.parse("2026-09-02 19:42:11 GMT", budapest))
        assertNull(DgsDates.parse("", budapest))
        assertNull(DgsDates.parse("yesterday", budapest))
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

        assertTrue(first.isMyTurn)

        val second = games[1]
        assertEquals(StoneColor.WHITE, second.myColor)
        assertEquals("hana_go", second.opponent.handle)
        assertNull(second.opponent.displayName)
        assertEquals(13, second.boardSize)
        assertEquals(2, second.handicap)
        assertEquals(Duration.ofHours(6), second.remainingTime?.main)
        assertTrue(second.isMyTurn)
    }

    @Test
    fun `running list marks whose turn it is`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_running.json")), "123456")
        assertEquals(2, games.size)
        // move_uid 123456 is me; move_uid 777 is the opponent.
        assertTrue(games[0].isMyTurn)
        assertEquals("opp_a", games[0].opponent.handle)
        assertFalse(games[1].isMyTurn)
        assertEquals("opp_b", games[1].opponent.handle)
    }

    @Test
    fun `finished list carries the result and nobody to move`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_finished.json")), "123456")
        assertEquals(3, games.size)

        val won = games[0]
        assertEquals("1600001", won.id.value)
        assertTrue(won.isFinished)
        // DGS omits move_color, move_uid and the clock for a finished game; none of that may
        // turn into "your turn" or an invented remaining time.
        assertFalse(won.isMyTurn)
        assertNull(won.remainingTime)
        assertEquals(StoneColor.BLACK, won.myColor)
        assertEquals(248, won.moveNumber)
        assertEquals(PendingAction.OTHER, won.pendingAction)
        assertEquals(GameResult(StoneColor.WHITE, 7.5, ResultReason.POINTS), won.result)

        assertEquals(GameResult(StoneColor.BLACK, null, ResultReason.RESIGNATION), games[1].result)
        assertEquals(StoneColor.BLACK, games[1].myColor)

        // VOID: a game that ended without a result, and the user played white in it.
        assertEquals(GameResult(null, null, ResultReason.NO_RESULT), games[2].result)
        assertEquals(StoneColor.WHITE, games[2].myColor)
    }

    @Test
    fun `a finished game carries what it did to the user's rating, in ranks`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_finished.json")), "123456")

        // DGS's own rating_diff, which is what this game was worth — a quarter of a stone.
        assertEquals(-0.25, assertNotNull(games[0].ratingChange), 0.001)
        assertEquals(0.34, assertNotNull(games[1].ratingChange), 0.001)

        // The unrated game says nothing. Its player's rank moved from 4k to 3k while it ran,
        // and reading that as the game's doing is exactly the trap: months of other games
        // moved it, and an unrated game moves nothing at all.
        assertNull(games[2].ratingChange)
    }

    @Test
    fun `own color falls back to move_color when the user id is unknown`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_status.json")), null)
        assertEquals(StoneColor.BLACK, games[0].myColor)
        assertEquals(StoneColor.WHITE, games[1].myColor)
    }

    @Test
    fun `a translated game list still yields times and colors`() {
        val games = GameObjectParser.parseList(QuickSuite.parse(Fixtures.text("game_list_status_hu.json")), "123456")
        assertEquals(2, games.size)
        assertEquals(StoneColor.BLACK, games[0].myColor)
        assertEquals("3n 20ó", games[0].remainingTime?.text)
        assertEquals(Duration.ofDays(3).plusHours(20), games[0].remainingTime?.main)
        assertEquals(StoneColor.WHITE, games[1].myColor)
        assertEquals(Duration.ofDays(1).plusHours(23), games[1].remainingTime?.main)
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
    fun `game info carries the settings and both players' standing`() {
        val info = GameInfoParser.parse(QuickSuite.parse(Fixtures.text("game_info.json")))
        assertEquals(0.5, info.komi)
        assertEquals(5, info.handicap)
        // DGS shouts "JAPANESE"; the app is sentence case throughout.
        assertEquals("Japanese", info.ruleset)
        assertEquals(true, info.rated)
        assertEquals("F: 30d (+ 1d)", info.timeLimit)
        assertEquals(true, info.weekendClock)
        assertEquals(Instant.parse("2015-06-24T10:00:00Z"), info.startedAt)
        // 0 in the payload means "not a tournament game".
        assertNull(info.tournament)

        assertEquals("fakeuser", info.black.handle)
        assertEquals("14k", info.black.rank)
        assertEquals("15k", info.black.rankAtStart)
        assertEquals(12, info.black.prisoners)
        // Both clocks, where the summary only carries the user's own.
        assertEquals(Duration.ofDays(8), info.black.remainingTime?.main)
        assertEquals("whitefake", info.white.handle)
        assertEquals(9, info.white.prisoners)
        assertEquals(Duration.ofDays(21).plusHours(5), info.white.remainingTime?.main)
        assertEquals("se", info.white.country)
    }

    @Test
    fun `a tournament game reports its tournament`() {
        val payload = Fixtures.text("game_info.json").replace("\"tournament_id\":0", "\"tournament_id\":1042")
        assertEquals("1042", GameInfoParser.parse(QuickSuite.parse(payload)).tournament?.id)
    }

    @Test
    fun `the SGF header names a ladder game's event and round`() {
        val merged =
            DgsTournament.merge(
                TournamentRef(id = "3"),
                mapOf(
                    "EV" to listOf("(Dragon Ladder) Tournament #3: DGS Ladder (19x19)"),
                    "RO" to listOf("Round 1 of 1"),
                ),
            )
        assertEquals("3", merged?.id)
        assertEquals("DGS Ladder (19x19)", merged?.name)
        assertEquals("Dragon Ladder", merged?.kind)
        assertEquals(true, merged?.isLadder)
        assertEquals(1, merged?.round)
        assertEquals(1, merged?.roundCount)
    }

    @Test
    fun `a non-ladder tournament is told apart by its kind`() {
        val merged =
            DgsTournament.merge(
                TournamentRef(id = "88"),
                mapOf(
                    "EV" to listOf("(Private Round Robin) Tournament #88: RazorBrain's private test tournament (5x5)"),
                    "RO" to listOf("Round 4 of 4"),
                ),
            )
        assertEquals("Private Round Robin", merged?.kind)
        assertEquals(false, merged?.isLadder)
        assertEquals("RazorBrain's private test tournament (5x5)", merged?.name)
        assertEquals(4, merged?.round)
        assertEquals(4, merged?.roundCount)
    }

    @Test
    fun `a casual game has no event in its SGF and no tournament`() {
        assertNull(DgsTournament.merge(null, mapOf("DT" to listOf("2007-04-21,2007-05-19"))))
    }

    @Test
    fun `an event line the client cannot read still keeps the id`() {
        // The tournament kind is translatable, so a wording we cannot parse must not lose the
        // fact that the game belongs to a tournament at all.
        val merged = DgsTournament.merge(TournamentRef(id = "3"), mapOf("EV" to listOf("Ranglétra")))
        assertEquals("3", merged?.id)
        assertNull(merged?.name)
        assertEquals(false, merged?.isLadder)
    }

    @Test
    fun `the scoring answer carries the marks, the territory and the score`() {
        // Shaped after the quick_suite.txt specification of status_score's reply: this
        // account has no game in the scoring phase to record, so the fixture states the
        // documented format rather than a captured response.
        val state = ScoreStateParser.parse(QuickSuite.parse(Fixtures.text("status_score.json")))
        assertEquals(ScoringRules.TERRITORY, state.rules)
        assertEquals(StoneColor.BLACK, state.result?.winner)
        assertEquals(7.5, state.result?.margin)
        assertEquals(setOf(SgfCoordinates.decodePoint("cd")), state.deadStones)
        assertEquals(3, state.blackTerritory.size)
        assertEquals(2, state.whiteTerritory.size)
        assertEquals(setOf(SgfCoordinates.decodePoint("ee")), state.dame)
    }

    @Test
    fun `coordinate lists parse with or without separators`() {
        val commas = ScoreStateParser.points("bc,ef,nj")
        assertEquals(commas, ScoreStateParser.points("bcefnj"))
        assertEquals(3, commas.size)
        assertTrue(ScoreStateParser.points("").isEmpty())
        assertTrue(ScoreStateParser.points(null).isEmpty())
        // A truncated list is a protocol error rather than a silently dropped stone.
        assertFailsWith<GoServerException.Protocol> { ScoreStateParser.points("bcd") }
    }

    @Test
    fun `every score line DGS can send is understood`() {
        assertEquals(ResultReason.JIGO, DgsScore.parse("0")?.by)
        assertEquals(ResultReason.NO_RESULT, DgsScore.parse("VOID")?.by)
        assertEquals(ResultReason.RESIGNATION, DgsScore.parse("W+R")?.by)
        assertEquals(ResultReason.TIMEOUT, DgsScore.parse("B+T")?.by)
        assertEquals(ResultReason.FORFEIT, DgsScore.parse("W+F")?.by)
        val onPoints = DgsScore.parse("W+7")
        assertEquals(StoneColor.WHITE, onPoints?.winner)
        assertEquals(7.0, onPoints?.margin)
        // A running game has no score line at all.
        assertNull(DgsScore.parse(""))
        assertNull(DgsScore.parse(null))
    }

    @Test
    fun `the ruleset says how the game will be counted`() {
        assertEquals(ScoringRules.AREA, DgsRulesets.scoring("CHINESE"))
        assertEquals(ScoringRules.TERRITORY, DgsRulesets.scoring("JAPANESE"))
        assertNull(DgsRulesets.scoring(""))
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
