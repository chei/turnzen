package dev.turnzen.goserver

import dev.turnzen.game.Move
import dev.turnzen.game.Point

/**
 * A correspondence (turn-based) Go server. Pull-based: the app fetches state when the
 * user opens it, acts, and closes it. Every method may throw a [GoServerException].
 *
 * Implementations hold their own session state (cookies). [exportSession] and
 * [restoreSession] let the app persist that state so the user is not signed in again on
 * every launch.
 */
interface TurnBasedGoServerClient {
    /** Human-readable server name for the UI ("Dragon Go Server"). */
    val serverName: String

    /** Registration page for users without an account, opened in the browser. */
    val registrationUrl: String

    suspend fun signIn(credentials: Credentials): SignedInUser

    suspend fun signOut()

    /** Verifies the current session and returns the signed-in user, or throws [GoServerException.SessionExpired]. */
    suspend fun currentUser(): SignedInUser

    /** Games in which it is the user's turn. Order is unspecified; the UI sorts. */
    suspend fun gamesAwaitingMe(): List<GameSummary>

    /**
     * All of the user's running games, whether it is their turn or the opponent's.
     * Each entry's [GameSummary.isMyTurn] says which. Order is unspecified; the UI sorts.
     */
    suspend fun myRunningGames(): List<GameSummary>

    suspend fun game(id: GameId): GameDetail

    /**
     * Sends exactly one move. [token] must be the one returned with the game state the
     * move was decided on; the server rejects it with [GoServerException.StaleGame] otherwise.
     */
    suspend fun sendMove(
        id: GameId,
        token: MoveToken,
        move: Move,
    )

    /**
     * Gives the game up. Irreversible on every server, so the caller is expected to have
     * confirmed it with the user. [token] is the same move context [sendMove] takes.
     */
    suspend fun resign(
        id: GameId,
        token: MoveToken,
    )

    /**
     * The scoring phase's current state: the dead stones both players are working from, the
     * territory they leave, and the server's own count.
     *
     * [toggle] previews a change without committing it — one point per group whose life the
     * user wants to flip — so a client can show what marking a group would be worth before
     * proposing it. Committing is [disputeScore] or [acceptScore].
     */
    suspend fun scoreState(
        id: GameId,
        token: MoveToken,
        toggle: Set<Point> = emptySet(),
    ): ScoreState

    /**
     * Proposes different dead stones: [toggle] carries one point per group to flip. This is
     * a move in the scoring phase — the turn passes to the opponent, who reviews the marks.
     */
    suspend fun disputeScore(
        id: GameId,
        token: MoveToken,
        toggle: Set<Point>,
    )

    /**
     * Agrees with the marks as they stand. When both players have agreed the game is over,
     * so this is as final as a resignation and should be confirmed with the user.
     */
    suspend fun acceptScore(
        id: GameId,
        token: MoveToken,
    )

    /**
     * Where the user stands in [tournament], keyed by game, for every running game of theirs
     * in it — empty when the tournament is not a ladder, when the user does not take part, or
     * when the server has no such notion at all, which is why the default is to answer nothing.
     *
     * Deliberately not part of [game]: it is decoration, it costs its own request, and a
     * caller must be free to decide it is not worth paying for. Deliberately *all* of the
     * user's games in one answer rather than one game at a time, because a server that has to
     * read a standings table to answer at all should be asked once, not once per game.
     */
    suspend fun ladderStandings(tournament: TournamentRef): Map<GameId, LadderStanding> = emptyMap()

    /**
     * The game's page on the server's website, for an "open on the web" link — the escape
     * hatch for everything this client cannot do yet. Null when the server has no such page.
     */
    fun gameUrl(id: GameId): String?

    /** Opaque session state to persist, or null when signed out. */
    fun exportSession(): String?

    fun restoreSession(session: String)
}
