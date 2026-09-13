package dev.turnzen.goserver

import dev.turnzen.game.Move

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
     * The game's page on the server's website, for an "open on the web" link — the escape
     * hatch for everything this client cannot do yet. Null when the server has no such page.
     */
    fun gameUrl(id: GameId): String?

    /** Opaque session state to persist, or null when signed out. */
    fun exportSession(): String?

    fun restoreSession(session: String)
}
