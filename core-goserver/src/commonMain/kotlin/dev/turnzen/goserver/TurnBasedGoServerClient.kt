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
    /** Which server this is. Every [GameId] this client hands out carries it. */
    val serverId: ServerId

    /** Human-readable server name for the UI ("Dragon Go Server"). */
    val serverName: String

    /** Registration page for users without an account, opened in the browser. */
    val registrationUrl: String

    /**
     * The web domain of the server's own sign-in page ("www.dragongoserver.net"), for
     * [SignInMethod.PASSWORD]. The sign-in fields report it to the device's password manager,
     * so a password the user saved on the website is offered in the app too. Null when there
     * is no such page.
     */
    val passwordDomain: String?
        get() = null

    /** How this server wants the user to sign in; decides which of the two sign-ins applies. */
    val signInMethod: SignInMethod
        get() = SignInMethod.PASSWORD

    /** For [SignInMethod.PASSWORD]. */
    suspend fun signIn(credentials: Credentials): SignedInUser

    /**
     * For [SignInMethod.BROWSER]: the user signs in on the server's own web page, so this
     * client never sees a password. [openPage] is called once with the page to show; the
     * call then waits until the server hands the sign-in back, and is cancelled like any
     * other coroutine when the user gives up.
     */
    suspend fun signInInBrowser(openPage: (url: String) -> Unit): SignedInUser =
        throw UnsupportedOperationException("$serverName signs in with a password")

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

    /**
     * The user's most recently finished games, at most [limit] of them, newest first —
     * the tail of their archive, not all of it. Bounded on purpose: a server's archive can
     * run to thousands of games, and no screen of this app browses one.
     *
     * The default answers nothing, for a server with no archive to read (and for test
     * doubles that do not care). Entries have [GameSummary.isFinished] set, and their
     * [GameSummary.moveToken] is not good for anything: there is no move to make.
     */
    suspend fun myFinishedGames(limit: Int): List<GameSummary> = emptyList()

    suspend fun game(id: GameId): GameDetail

    /**
     * The players' comments in a game, oldest first, for a server whose [game] does not bring
     * them along ([GameDetail.comments] is null). A request of its own, so the caller decides
     * whether it is worth one; the default answers nothing, for a server with no comments.
     */
    suspend fun comments(id: GameId): List<GameComment> = emptyList()

    /**
     * Sends exactly one move. [token] must be the one returned with the game state the
     * move was decided on; the server rejects it with [GoServerException.StaleGame] otherwise.
     *
     * [comment], when there is one, is the player's message to go with the move. Where the
     * server keeps a message as part of the move (DGS) it travels in the same request. Where a
     * message is a thing of its own (OGS chat) it follows the move, and failing to deliver it
     * does not fail a move the server has already accepted.
     */
    suspend fun sendMove(
        id: GameId,
        token: MoveToken,
        move: Move,
        comment: String? = null,
    )

    /**
     * Gives the game up. Irreversible on every server, so the caller is expected to have
     * confirmed it with the user. [token] is the same move context [sendMove] takes.
     *
     * [comment], when there is one, is the player's parting message. On DGS it travels with
     * the resignation, as a message does with a move. On OGS it is a chat line sent just
     * before, while the game is still running; failing to deliver it does not stop the
     * resignation.
     */
    suspend fun resign(
        id: GameId,
        token: MoveToken,
        comment: String? = null,
    )

    /**
     * False when this client cannot take part in the scoring phase, so the UI sends the user
     * to [gameUrl] for it instead of calling [scoreState] and friends.
     */
    val scoresInApp: Boolean
        get() = true

    /**
     * The scoring phase's current state: the dead stones both players are working from, the
     * territory they leave, and the server's own count — or, on a server that counts nothing
     * until the game is over (OGS), the client's count of the same marks.
     *
     * [toggle] previews a change without committing it, so a client can show what marking
     * stones would be worth before proposing it. Committing is [disputeScore] or [acceptScore].
     */
    suspend fun scoreState(
        id: GameId,
        token: MoveToken,
        toggle: List<Point> = emptyList(),
    ): ScoreState

    /**
     * Proposes different dead stones. [toggle] is the user's taps, in the order they made
     * them, each flipping an area the way `DeadStones.toggle` does; the server replays them on
     * its own marks. This is a move in the scoring phase — the turn passes to the opponent,
     * who reviews the marks. Where both players mark one shared set at once (OGS), the user
     * also accepts the marks they send, which leaves the opponent the same choice.
     */
    suspend fun disputeScore(
        id: GameId,
        token: MoveToken,
        toggle: List<Point>,
    )

    /**
     * Agrees with the marks as they stand — the ones [scoreState] last showed; a client throws
     * [GoServerException.StaleGame] rather than agree to marks that changed since. When both
     * players have agreed the game is over, so this is as final as a resignation and should be
     * confirmed with the user.
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

    /** The user's own page on the server's website, or null when there is none worth linking. */
    fun accountUrl(user: SignedInUser): String? = null

    /**
     * Opaque session state to persist, or null when signed out. It can change on any call —
     * a server whose tokens are renewed as they age hands out a new pair — so a caller that
     * persists it should do so again after calls, not only after [signIn].
     */
    fun exportSession(): String?

    fun restoreSession(session: String)
}

/** How a server signs its users in. */
enum class SignInMethod {
    /** Username and password, typed into the app. */
    PASSWORD,

    /** On the server's own web page, in the browser; the app never sees the password. */
    BROWSER,
}
