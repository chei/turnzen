package dev.turnzen.goserver

import dev.turnzen.game.GameRecord
import dev.turnzen.game.GameResult
import dev.turnzen.game.Point
import dev.turnzen.game.ScoringRules
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
    /** True when the server is waiting for this user to act; false when it is the opponent's turn. */
    val isMyTurn: Boolean,
    val moveToken: MoveToken,
)

/**
 * One player as the server describes them *inside a game*: who they are, how they stood when
 * the game began and how they stand now, and their own clock — which, unlike
 * [GameSummary.remainingTime], is reported for both sides whether or not it is running.
 *
 * Every field but [handle] is optional: a server need not report it, and a screen has to
 * degrade to showing less rather than inventing a value.
 */
data class PlayerDetail(
    val handle: String,
    val displayName: String? = null,
    val rank: String? = null,
    /** Country code the server reports for the player, if any. */
    val country: String? = null,
    /** The rank this player held when the game started, where the server keeps it. */
    val rankAtStart: String? = null,
    /** Stones this player has captured so far. */
    val prisoners: Int? = null,
    val remainingTime: RemainingTime? = null,
)

/**
 * What a game was set up as, and where both players stand in it — everything the list row
 * has no room for. Read-only context: nothing here is needed to play a move.
 */
data class GameInfo(
    val black: PlayerDetail,
    val white: PlayerDetail,
    val komi: Double? = null,
    val handicap: Int = 0,
    /** The server's own name for the rules ("Japanese"), ready to display. */
    val ruleset: String? = null,
    val rated: Boolean? = null,
    /** The time system, formatted by the server (and so in the account's language). */
    val timeLimit: String? = null,
    /** True when the clock is paused at weekends. */
    val weekendClock: Boolean? = null,
    val startedAt: Instant? = null,
    /** The tournament this game belongs to, or null for a casual game. */
    val tournament: TournamentRef? = null,
    /** How the game will be counted, when the server says which rules it uses. */
    val scoring: ScoringRules? = null,
)

/**
 * The tournament a game was played for, as much of it as the server volunteers with the game.
 *
 * A pointer, not the tournament itself: standings, pairings and a ladder's rankings all live
 * elsewhere on the server, usually behind a page rather than an API. What is here is enough
 * to tell the player *which* event this game belongs to and where in it they are.
 */
data class TournamentRef(
    /** The server's id, the one its own web pages use. */
    val id: String,
    /** The tournament's title, when the server tells us one. */
    val name: String? = null,
    /**
     * The server's own wording for what kind of tournament it is ("Dragon Ladder",
     * "Private Round Robin"), shown as it came: it may be in the account's language.
     */
    val kind: String? = null,
    /**
     * True when this is a ladder — a standings list you climb by challenging players above
     * you — rather than a bracket or a league. Best effort: it is read out of [kind], which
     * the server may have translated.
     */
    val isLadder: Boolean = false,
    /** Which round this game was played in, where the tournament has rounds. */
    val round: Int? = null,
    /** How many rounds the tournament has in total. */
    val roundCount: Int? = null,
)

/**
 * Where the two players of one running game stand on a ladder, from the user's own seat.
 *
 * A ladder is climbed by challenging someone above you, so the interesting fact is not two
 * numbers but a direction: who called whom out. [youAreChallenger] carries it, because the
 * ranks cannot — a challenge runs for weeks and either player may pass the other in the
 * meantime, so the challenger is not reliably the lower-placed of the two by the time anyone
 * reads this.
 *
 * Decoration, never required: a game is played the same whether or not this is known.
 */
data class LadderStanding(
    val yourRank: Int,
    val opponentRank: Int,
    /** True when the user issued the challenge, false when they are defending one. */
    val youAreChallenger: Boolean,
)

/**
 * The scoring phase as the server sees it: which stones are being treated as dead, the
 * territory that leaves, and the result it comes to. The server counts; this is its answer,
 * not ours, and it is what an agreement agrees to.
 */
data class ScoreState(
    /** Null when the server sent a score this client could not read. */
    val result: GameResult? = null,
    val rules: ScoringRules? = null,
    val deadStones: Set<Point> = emptySet(),
    val blackTerritory: Set<Point> = emptySet(),
    val whiteTerritory: Set<Point> = emptySet(),
    /** Empty points that belong to neither side. */
    val dame: Set<Point> = emptySet(),
)

data class GameDetail(
    val summary: GameSummary,
    val record: GameRecord,
    /** Null when the server told us nothing beyond the summary. */
    val info: GameInfo? = null,
)
