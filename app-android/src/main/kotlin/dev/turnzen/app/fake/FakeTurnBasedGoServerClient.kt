package dev.turnzen.app.fake

import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Coordinates
import dev.turnzen.game.GameRecord
import dev.turnzen.game.Move
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.Replay
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameInfo
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.PlayerDetail
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.SignedInUser
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
    )

    private val games: MutableList<FakeGame> = mutableListOf(demo19(), demo13(), demo9(), demoWaiting())

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
        // A resigned game is over, so it leaves both lists exactly as it would on a real server.
        games.remove(g)
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
        games.firstOrNull { it.id == id } ?: throw GoServerException.ServerError("unknown_game", "Unknown game")

    private fun FakeGame.summary() =
        GameSummary(
            id = id,
            opponent = opponent,
            myColor = myColor,
            boardSize = record.boardSize,
            handicap = record.handicap,
            moveNumber = record.moves.size,
            lastMoveAt = lastMoveAt,
            remainingTime = remaining,
            pendingAction = PendingAction.PLAY_MOVE,
            isMyTurn = awaitingMe,
            moveToken = MoveToken(moveId.toString()),
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
            tournamentId = if (id.value == "demo-13") "1042" else null,
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
    }
}
