package dev.turnzen.goserver

import dev.turnzen.game.GameRecord
import dev.turnzen.game.GameResult
import dev.turnzen.game.Point
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

data class Credentials(
    val username: String,
    val password: String,
)

/**
 * Which server something belongs to ("dgs", "ogs"). Opaque: the UI may use it to tell
 * servers apart (a colour, a name), never to decide how to talk to one. Each server module
 * defines its own.
 */
@JvmInline
value class ServerId(
    val value: String,
)

/**
 * Identifies a game: the server it is played on, and that server's own id for it. Carrying
 * the server keeps ids from different servers apart (both DGS and OGS number their games),
 * and tells the UI which server a game belongs to. [value] is opaque to the UI.
 */
data class GameId(
    val server: ServerId,
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
    /**
     * True when the game is over. Nothing can be sent for it any more, whatever the rest of
     * this summary says: a finished game reports no clock and nobody to move.
     */
    val isFinished: Boolean = false,
    /**
     * How the game ended, for a finished game whose result the server stated. Null while the
     * game runs, and also for a finished game the server gave no readable score for.
     */
    val result: GameResult? = null,
    /**
     * What the finished game did to this user's rating, in ranks: +0.23 is a bit under a
     * quarter of a stone, and a player at 4k exactly who gains it stands at 4k +0.23.
     *
     * The game's own doing, and nothing else — not the difference between the ranks the
     * player held at the two ends of it, which in a game that ran for months is mostly other
     * games. Null for an unrated game and for a server that reports no such thing.
     */
    val ratingChange: Double? = null,
    /**
     * Rows of the board when it is not square (OGS allows 19×9); equal to [boardSize] for
     * every board this client draws.
     */
    val boardHeight: Int = boardSize,
    /**
     * True for a game this client lists but cannot show — a board shape it does not draw, a
     * team game. The UI sends the user to [TurnBasedGoServerClient.gameUrl] instead.
     */
    val onlyOnWeb: Boolean = false,
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
    /** What a finished, rated game did to this player's rating, in ranks. See [GameSummary]. */
    val ratingChange: Double? = null,
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
    /** Each side's territory, including the points its opponent's dead stones stand on. */
    val blackTerritory: Set<Point> = emptySet(),
    val whiteTerritory: Set<Point> = emptySet(),
    /** Empty points that belong to neither side. */
    val dame: Set<Point> = emptySet(),
)

/**
 * Who can read a comment. Servers draw these lines differently, so this names the reader's
 * situation rather than any one server's feature; each server maps its own kinds onto it.
 */
enum class CommentVisibility {
    /** Anyone who can see the game, observers included. */
    EVERYONE,

    /** The two players only. */
    PLAYERS,

    /** Only its writer while the game runs; everyone once it is over. */
    HIDDEN_UNTIL_END,

    /** Only its writer while the game runs; the two players, never observers, once it is over. */
    SECRET_UNTIL_END,

    /** Only its writer, ever: a note to self. */
    WRITER_ONLY,
}

/**
 * Something a player wrote in a game, pinned to the position it was written at.
 *
 * Only the players' own words: text a server adds by itself (a result, a scoring summary)
 * is not a comment, and neither are observers' remarks, which this client leaves out.
 */
data class GameComment(
    /**
     * How many of [GameRecord.moves] had been played when it was written: 0 for a comment
     * made before the first move (DGS attaches the handicap setup's message there), `n` for
     * one sent with, or right after, the n-th move.
     */
    val afterMove: Int,
    /** The player who wrote it. */
    val by: StoneColor,
    val text: String,
    val visibility: CommentVisibility = CommentVisibility.PLAYERS,
)

data class GameDetail(
    val summary: GameSummary,
    val record: GameRecord,
    /** Null when the server told us nothing beyond the summary. */
    val info: GameInfo? = null,
    /**
     * The players' comments, oldest first, when the server hands them over with the game at
     * no extra cost. Null when it does not, and [TurnBasedGoServerClient.comments] has to be
     * asked separately — an empty list means the game really has none.
     */
    val comments: List<GameComment>? = null,
)
