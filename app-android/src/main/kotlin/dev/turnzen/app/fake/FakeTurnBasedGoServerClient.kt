package dev.turnzen.app.fake

import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Coordinates
import dev.turnzen.game.GameRecord
import dev.turnzen.game.GameResult
import dev.turnzen.game.Move
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.Point
import dev.turnzen.game.Replay
import dev.turnzen.game.ResultReason
import dev.turnzen.game.Scoring
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameInfo
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.PlayerDetail
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TournamentRef
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * In-memory server for previews and for developing the UI without DGS. Sending a move
 * appends it to the record and lets the "opponent" answer with a pass.
 */
class FakeTurnBasedGoServerClient(
    private val latencyMillis: Long = 300,
) : TurnBasedGoServerClient {
    override val serverName = "Demo server"
    override val registrationUrl = "https://www.dragongoserver.net/register.php"

    private var signedIn = false
    private val user = SignedInUser("1", "demo", "Demo User", "8k")

    private class FakeGame(
        val id: GameId,
        val opponent: Opponent,
        val myColor: StoneColor,
        var record: GameRecord,
        var moveId: Int,
        var lastMoveAt: Instant,
        val remaining: RemainingTime,
        var awaitingMe: Boolean = true,
        /** True once the game has reached the counting phase. */
        val scoring: Boolean = false,
        /** The dead stones both players are working from, as the server holds them. */
        val dead: MutableSet<Point> = LinkedHashSet(),
        /** Set when the game is over, which takes it out of [games] and into [archive]. */
        var result: GameResult? = null,
        var finished: Boolean = false,
    )

    private val games: MutableList<FakeGame> =
        mutableListOf(demo19(), demo13(), demo9(), demoWaiting(), demoScoring())

    /** Finished games, newest first — including the ones ended during this demo session. */
    private val archive: MutableList<FakeGame> = mutableListOf(demoFinishedWin(), demoFinishedLoss())

    override suspend fun signIn(credentials: Credentials): SignedInUser {
        delay(latencyMillis)
        if (credentials.password == "wrong") throw GoServerException.InvalidCredentials()
        signedIn = true
        return user
    }

    override suspend fun signOut() {
        signedIn = false
    }

    override suspend fun currentUser(): SignedInUser {
        if (!signedIn) throw GoServerException.SessionExpired()
        return user
    }

    override suspend fun gamesAwaitingMe(): List<GameSummary> {
        delay(latencyMillis)
        requireSession()
        return games.filter { it.awaitingMe }.map { it.summary() }
    }

    override suspend fun myRunningGames(): List<GameSummary> {
        delay(latencyMillis)
        requireSession()
        return games.map { it.summary() }
    }

    override suspend fun myFinishedGames(limit: Int): List<GameSummary> {
        delay(latencyMillis)
        requireSession()
        return archive.take(limit).map { it.summary() }
    }

    override suspend fun game(id: GameId): GameDetail {
        delay(latencyMillis)
        requireSession()
        val g = find(id)
        return GameDetail(g.summary(), g.record, g.info())
    }

    override suspend fun sendMove(
        id: GameId,
        token: MoveToken,
        move: Move,
    ) {
        delay(latencyMillis)
        requireSession()
        val g = find(id)
        if (token.value != g.moveId.toString()) throw GoServerException.StaleGame()
        val opponentColor = g.myColor.opposite
        g.record =
            g.record.copy(
                moves = g.record.moves + ColoredMove(g.myColor, move) + ColoredMove(opponentColor, Move.Pass),
            )
        g.moveId += 2
        g.lastMoveAt = Instant.now()
    }

    override suspend fun resign(
        id: GameId,
        token: MoveToken,
    ) {
        delay(latencyMillis)
        requireSession()
        val g = find(id)
        if (token.value != g.moveId.toString()) throw GoServerException.StaleGame()
        // A resigned game is over, so it leaves both lists exactly as it would on a real
        // server — and turns up in the archive, which is also what a real server does.
        g.finish(GameResult(g.myColor.opposite, null, ResultReason.RESIGNATION))
    }

    /**
     * Counting works here the way it works on a real server: the marks live on the server,
     * a client may preview toggles without committing them, and the count comes back with
     * the territory it is based on. The arithmetic is [Scoring]'s, the same code the app
     * shows live while marking.
     */
    override suspend fun scoreState(
        id: GameId,
        token: MoveToken,
        toggle: Set<Point>,
    ): ScoreState {
        delay(latencyMillis)
        requireSession()
        val g = requireScoring(id)
        return g.stateFor(g.toggled(toggle))
    }

    override suspend fun disputeScore(
        id: GameId,
        token: MoveToken,
        toggle: Set<Point>,
    ) {
        delay(latencyMillis)
        requireSession()
        val g = requireScoring(id)
        if (token.value != g.moveId.toString()) throw GoServerException.StaleGame()
        val marked = g.toggled(toggle)
        g.dead.clear()
        g.dead += marked
        g.moveId += 1
        g.lastMoveAt = Instant.now()
        // The opponent now reviews the marks, so the turn passes exactly as after a move.
        g.awaitingMe = false
    }

    override suspend fun acceptScore(
        id: GameId,
        token: MoveToken,
    ) {
        delay(latencyMillis)
        requireSession()
        val g = requireScoring(id)
        if (token.value != g.moveId.toString()) throw GoServerException.StaleGame()
        // The demo opponent always agrees, so accepting finishes the game.
        g.finish(g.stateFor(g.dead).result)
    }

    /** Ends a game: out of the running list, in at the top of the archive. */
    private fun FakeGame.finish(outcome: GameResult?) {
        games.remove(this)
        result = outcome
        finished = true
        awaitingMe = false
        lastMoveAt = Instant.now()
        archive.add(0, this)
    }

    private fun requireScoring(id: GameId): FakeGame {
        val g = find(id)
        if (!g.scoring) throw GoServerException.MoveRejected("invalid_action", "Game is not being scored")
        return g
    }

    /** The marks after flipping the group behind each toggled point. */
    private fun FakeGame.toggled(toggle: Set<Point>): Set<Point> {
        val board = Replay(record).finalPosition.board
        val marked = LinkedHashSet(dead)
        for (point in toggle) {
            val group = Scoring.groupAt(board, point)
            if (group.isEmpty()) continue
            if (group.all { it in marked }) marked -= group else marked += group
        }
        return marked
    }

    private fun FakeGame.stateFor(marked: Set<Point>): ScoreState {
        val position = Replay(record).finalPosition
        val count = Scoring.count(position, marked, record.komi ?: 0.0, ScoringRules.TERRITORY)
        return ScoreState(
            result = count.result,
            rules = ScoringRules.TERRITORY,
            deadStones = marked,
            blackTerritory = count.territoryOf(StoneColor.BLACK),
            whiteTerritory = count.territoryOf(StoneColor.WHITE),
            dame = count.dame,
        )
    }

    /** The demo ladder: one challenge the user started, so the sheet has a standing to show. */
    override suspend fun ladderStandings(tournament: TournamentRef): Map<GameId, LadderStanding> =
        if (tournament.isLadder) {
            mapOf(GameId("demo-13") to LadderStanding(yourRank = 12, opponentRank = 7, youAreChallenger = true))
        } else {
            emptyMap()
        }

    override fun gameUrl(id: GameId): String = "https://www.dragongoserver.net/game.php?gid=${id.value}"

    override fun exportSession(): String? = if (signedIn) "demo" else null

    override fun restoreSession(session: String) {
        signedIn = session == "demo"
    }

    private fun requireSession() {
        if (!signedIn) throw GoServerException.SessionExpired()
    }

    private fun find(id: GameId) =
        games.firstOrNull { it.id == id }
            ?: archive.firstOrNull { it.id == id }
            ?: throw GoServerException.ServerError("unknown_game", "Unknown game")

    private fun FakeGame.summary() =
        GameSummary(
            id = id,
            opponent = opponent,
            myColor = myColor,
            boardSize = record.boardSize,
            handicap = record.handicap,
            moveNumber = record.moves.size,
            lastMoveAt = lastMoveAt,
            // A finished game has no clock left to report, exactly as DGS reports none.
            remainingTime = if (finished) null else remaining,
            pendingAction =
                when {
                    finished -> PendingAction.OTHER
                    scoring -> PendingAction.SCORE
                    else -> PendingAction.PLAY_MOVE
                },
            isMyTurn = awaitingMe && !finished,
            moveToken = MoveToken(moveId.toString()),
            isFinished = finished,
            result = result,
        )

    /** The demo server's stand-in for what DGS reports about a game beyond the summary. */
    private fun FakeGame.info(): GameInfo {
        val prisoners = Replay(record).finalPosition.prisoners

        fun side(color: StoneColor): PlayerDetail {
            val player = if (color == StoneColor.BLACK) record.black else record.white
            val mine = color == myColor
            return PlayerDetail(
                handle = if (mine) user.handle else opponent.handle,
                displayName = player.name,
                rank = player.rank,
                country = if (mine) "ch" else "de",
                rankAtStart = player.rank,
                prisoners = prisoners[color] ?: 0,
                remainingTime = remaining,
            )
        }
        return GameInfo(
            black = side(StoneColor.BLACK),
            white = side(StoneColor.WHITE),
            komi = record.komi,
            handicap = record.handicap,
            ruleset = "Japanese",
            rated = true,
            timeLimit = "F: 30d (+ 1d)",
            weekendClock = true,
            startedAt = lastMoveAt.minus(Duration.ofDays(40)),
            tournament =
                if (id.value == "demo-13") {
                    TournamentRef(
                        id = "1042",
                        name = "Turnzen Demo Ladder (13x13)",
                        kind = "Dragon Ladder",
                        isLadder = true,
                        round = 1,
                        roundCount = 1,
                    )
                } else {
                    null
                },
        )
    }

    private companion object {
        fun moves(
            size: Int,
            labels: String,
        ): List<ColoredMove> {
            var color = StoneColor.BLACK
            return labels.split(' ').filter { it.isNotBlank() }.map { label ->
                val point = checkNotNull(Coordinates.parse(label, size)) { "bad label $label" }
                ColoredMove(color, Move.Play(point)).also { color = color.opposite }
            }
        }

        fun demo19(): FakeGame {
            val record =
                GameRecord(
                    boardSize = 19,
                    komi = 6.5,
                    moves =
                        moves(
                            19,
                            "Q16 D4 Q4 D16 R14 C14 F17 F3 D3 E3 C3 C4 D2 B4 O17 Q10 R10 Q9 R9 Q8 R7 P12 O3 K16 " +
                                "K17 J16 H17 L16 K4 K3 J3 L3 L4 M4 L5 M3 R12 Q12 Q13 P13 P14 O14",
                        ),
                    black = PlayerInfo("Demo User", "8k"),
                    white = PlayerInfo("mkvtl", "7k"),
                )
            return FakeGame(
                id = GameId("demo-19"),
                opponent = Opponent("mkvtl", null, "7k"),
                myColor = StoneColor.BLACK,
                record = record,
                moveId = record.moves.size,
                lastMoveAt = Instant.now().minus(Duration.ofMinutes(18)),
                remaining = RemainingTime("3d 4h", Duration.ofDays(3).plusHours(4), "+ 1d * 10"),
            )
        }

        fun demo13(): FakeGame {
            val record =
                GameRecord(
                    boardSize = 13,
                    komi = 0.5,
                    handicap = 2,
                    setupBlack = listOf(Coordinates.parse("D10", 13)!!, Coordinates.parse("K4", 13)!!),
                    moves =
                        moves(13, "D4 K10 C7 G3 F10 K7 E3 J4").map { it.copy(color = it.color.opposite) },
                    black = PlayerInfo("hana_go", "9k"),
                    white = PlayerInfo("Demo User", "8k"),
                )
            return FakeGame(
                id = GameId("demo-13"),
                opponent = Opponent("hana_go", null, "9k"),
                myColor = StoneColor.WHITE,
                record = record,
                moveId = record.moves.size + 1,
                lastMoveAt = Instant.now().minus(Duration.ofHours(2)),
                remaining = RemainingTime("6h", Duration.ofHours(6), "+ 15d / 8"),
            )
        }

        fun demo9(): FakeGame {
            val record =
                GameRecord(
                    boardSize = 9,
                    komi = 5.5,
                    moves = moves(9, "C7 G3 G7 C3 E7 E3 F4 F5 G5 E5 D5 D4"),
                    black = PlayerInfo("Demo User", "8k"),
                    white = PlayerInfo("BerndW", "6k"),
                )
            return FakeGame(
                id = GameId("demo-9"),
                opponent = Opponent("BerndW", "Bernd", "6k"),
                myColor = StoneColor.BLACK,
                record = record,
                moveId = record.moves.size,
                lastMoveAt = Instant.now().minus(Duration.ofDays(1)),
                remaining = RemainingTime("5d", Duration.ofDays(5), "+ 1d"),
            )
        }

        /**
         * A 9x9 that has run to two passes and is waiting to be counted. One white stone sits
         * stranded inside black's area: while it counts as alive the whole top of the board is
         * nobody's, and marking it dead turns that into eighteen points and a prisoner — which
         * is the point of the marking step, in one tap.
         */
        fun demoScoring(): FakeGame {
            val size = 9

            fun point(label: String) = checkNotNull(Coordinates.parse(label, size)) { "bad label $label" }
            val moves = ArrayList<ColoredMove>()
            val columns = listOf("A", "B", "C", "D", "E", "F", "G", "H", "J")
            for (column in columns) {
                moves += ColoredMove(StoneColor.BLACK, Move.Play(point("${column}7")))
                moves += ColoredMove(StoneColor.WHITE, Move.Play(point("${column}5")))
            }
            moves += ColoredMove(StoneColor.BLACK, Move.Pass)
            moves += ColoredMove(StoneColor.WHITE, Move.Play(point("D8")))
            moves += ColoredMove(StoneColor.BLACK, Move.Pass)
            moves += ColoredMove(StoneColor.WHITE, Move.Pass)
            val record =
                GameRecord(
                    boardSize = size,
                    komi = 6.5,
                    moves = moves,
                    black = PlayerInfo("Demo User", "8k"),
                    white = PlayerInfo("kaya_r", "8k"),
                )
            return FakeGame(
                id = GameId("demo-scoring"),
                opponent = Opponent("kaya_r", null, "8k"),
                myColor = StoneColor.BLACK,
                record = record,
                moveId = record.moves.size,
                lastMoveAt = Instant.now().minus(Duration.ofMinutes(40)),
                remaining = RemainingTime("2d", Duration.ofDays(2), "+ 1d * 10"),
                scoring = true,
            )
        }

        /** A game where the opponent is to move: shows in "All games" but not "Your turn". */
        fun demoWaiting(): FakeGame {
            val record =
                GameRecord(
                    boardSize = 19,
                    komi = 6.5,
                    // Odd number of moves: last is Black's, so White (the opponent) is to move.
                    moves = moves(19, "Q16 D4 Q4 D16 C14"),
                    black = PlayerInfo("Demo User", "8k"),
                    white = PlayerInfo("kaya_go", "5k"),
                )
            return FakeGame(
                id = GameId("demo-waiting"),
                opponent = Opponent("kaya_go", null, "5k"),
                myColor = StoneColor.BLACK,
                record = record,
                moveId = record.moves.size,
                lastMoveAt = Instant.now().minus(Duration.ofHours(5)),
                remaining = RemainingTime("9d", Duration.ofDays(9), "+ 1d"),
                awaitingMe = false,
            )
        }

        /**
         * An archived win on points, so the Finished tab has something in it from the start.
         * It ends the way every counted game ends — two passes — which is also what the
         * board's opening cursor has to step over.
         */
        fun demoFinishedWin(): FakeGame {
            val record =
                GameRecord(
                    boardSize = 9,
                    komi = 6.5,
                    moves =
                        moves(9, "E5 G7 C3 G3 E7 E3 D4 F4 F5 G5 G4 H4") +
                            ColoredMove(StoneColor.BLACK, Move.Pass) +
                            ColoredMove(StoneColor.WHITE, Move.Pass),
                    black = PlayerInfo("Demo User", "8k"),
                    white = PlayerInfo("nori_88", "8k"),
                )
            return FakeGame(
                id = GameId("demo-done-win"),
                opponent = Opponent("nori_88", null, "8k"),
                myColor = StoneColor.BLACK,
                record = record,
                moveId = record.moves.size,
                lastMoveAt = Instant.now().minus(Duration.ofDays(3)),
                remaining = RemainingTime("–"),
                awaitingMe = false,
                result = GameResult(StoneColor.BLACK, 12.5, ResultReason.POINTS),
                finished = true,
            )
        }

        /** And an archived loss, because a list of only wins would flatter the demo. */
        fun demoFinishedLoss(): FakeGame {
            val record =
                GameRecord(
                    boardSize = 19,
                    komi = 6.5,
                    moves = moves(19, "Q16 D4 Q4 D16 R14 C14 F17 F3 D3 E3 C3 C4"),
                    black = PlayerInfo("tsuke_42", "6k"),
                    white = PlayerInfo("Demo User", "8k"),
                )
            return FakeGame(
                id = GameId("demo-done-loss"),
                opponent = Opponent("tsuke_42", null, "6k"),
                myColor = StoneColor.WHITE,
                record = record,
                moveId = record.moves.size,
                lastMoveAt = Instant.now().minus(Duration.ofDays(9)),
                remaining = RemainingTime("–"),
                awaitingMe = false,
                result = GameResult(StoneColor.BLACK, null, ResultReason.RESIGNATION),
                finished = true,
            )
        }
    }
}
