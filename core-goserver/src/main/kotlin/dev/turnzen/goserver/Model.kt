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
 * Remaining thinking time of the player to move.
 *
 * Servers format this for the account holder, in their language and their preferred
 * units, so [text] is the server's own wording and is what the UI shows. [main] is that
 * same time as a duration, and is null when the client could not interpret the wording;
 * it exists only so the UI can decide whether the time is running out.
 */
data class RemainingTime(
    val text: String,
    val main: Duration? = null,
    /** Server-formatted description of what follows the main time (byo-yomi, increment). */
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
