package dev.turnzen.goserver

import dev.turnzen.game.GameRecord
import dev.turnzen.game.StoneColor
import java.time.Duration
import java.time.Instant

data class Credentials(
    val username: String,
    val password: String,
)

/** Identifies a game on one server. Opaque to the UI. */
@JvmInline
value class GameId(
    val value: String,
)

/**
 * Opaque context a server needs to accept a move for a specific game state (DGS calls
 * it `move_id`). Sending with a stale token means the game changed meanwhile.
 */
@JvmInline
value class MoveToken(
    val value: String,
)

data class SignedInUser(
    val id: String,
    val handle: String,
    val displayName: String?,
    val rank: String?,
)

data class Opponent(
    val handle: String,
    val displayName: String?,
    val rank: String?,
)

/** What the server expects from the user next. */
enum class PendingAction {
    PLAY_MOVE,
    PLACE_HANDICAP,
    SCORE,
    NEGOTIATE_KOMI,
    OTHER,
}

/**
 * Remaining thinking time of the player to move. [main] is the main time; [extra] is a
 * server-formatted description of what follows it (byo-yomi periods, Fischer increment),
 * or null if unknown.
 */
data class RemainingTime(
    val main: Duration,
    val extra: String? = null,
)

data class GameSummary(
    val id: GameId,
    val opponent: Opponent,
    val myColor: StoneColor,
    val boardSize: Int,
    val handicap: Int,
    /** Number of moves played so far (server counting). */
    val moveNumber: Int,
    val lastMoveAt: Instant?,
    val remainingTime: RemainingTime?,
    val pendingAction: PendingAction,
    val moveToken: MoveToken,
)

data class GameDetail(
    val summary: GameSummary,
    val record: GameRecord,
)
