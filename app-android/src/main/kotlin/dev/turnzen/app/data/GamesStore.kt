package dev.turnzen.app.data

import dev.turnzen.game.DeadStones
import dev.turnzen.game.Point
import dev.turnzen.game.Position
import dev.turnzen.game.Replay
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What the last refresh brought into each running list: the rows the Games screen has not
 * yet drawn attention to. Consumed one row at a time, as each highlight plays out.
 */
data class Arrivals(
    val yourTurn: Set<GameId> = emptySet(),
    val allRunning: Set<GameId> = emptySet(),
)

/**
 * A game's scoring phase as the server last described it, held so that reopening the game
 * shows its marks at once instead of a plain board that switches to scoring a moment later.
 *
 * Tied to [token]: every mark sent or accepted is a move, so while the token stands the
 * server's marks cannot have changed.
 */
data class HeldScore(
    val token: MoveToken,
    val state: ScoreState,
    /** The taps of the device's guess at the dead stones; empty when there is none to make. */
    val guess: List<Point>,
)

/**
 * The game lists shown on the Games screen and used by the board screen's auto-advance.
 *
 * Three lists, all sorted with the most recent move first:
 * - [yourTurn]: games where it is the user's move (the "Your turn" tab, and the queue the
 *   board screen advances through after a Send).
 * - [allRunning]: every running game, whichever side is to move (the "All games" tab).
 * - [finished]: the tail of the archive, for the "Finished" tab. Fetched on its own, only
 *   when that tab is opened: finished games do not change, so polling them with every
 *   refresh would spend the server's quota on an answer that is already known.
 */
class GamesStore(
    private val accounts: Accounts,
    /** The taps that mark the guessed dead stones on a board nobody has marked yet. */
    private val guessDeadStones: (Position) -> List<Point> = { DeadStones.guessTaps(it) },
    /** Where the guess runs: it plays a few hundred games out, too much for the main thread. */
    private val computeContext: CoroutineContext = Dispatchers.Default,
    /**
     * Told what each answering server said is awaiting the user, after every [refresh]: the
     * user has now seen those games, so the notifications need not announce them.
     */
    private val onYourTurnSeen: (Map<ServerId, List<GameSummary>>) -> Unit = {},
) {
    // What the servers last said. The published lists below are these with [outgoing] applied.
    private val _yourTurn = MutableStateFlow<List<GameSummary>?>(null)
    private val _allRunning = MutableStateFlow<List<GameSummary>?>(null)
    private val shownYourTurn = MutableStateFlow<List<GameSummary>?>(null)
    private val shownAllRunning = MutableStateFlow<List<GameSummary>?>(null)

    /**
     * Games whose move is on its way (see [MoveOutbox]): the user has played, the server has
     * not heard yet. Shown as already played — a refresh while it is on the wire must not put
     * the game back into the queue the user has just worked past.
     */
    private var outgoing: Set<GameId> = emptySet()
    private val _finished = MutableStateFlow<List<GameSummary>?>(null)
    private val _arrivals = MutableStateFlow(Arrivals())

    /**
     * Full records fetched ahead of time, so opening those games needs no download. Kept
     * small and in insertion order: the oldest entry is dropped once the cap is reached.
     * Only ever touched from the main dispatcher (view-model scopes), so a plain map is fine.
     */
    private val details = LinkedHashMap<GameId, GameDetail>()

    /** Scoring states of games in the counting phase, kept like [details]. */
    private val scores = LinkedHashMap<GameId, HeldScore>()

    /** null until the first successful load. */
    val yourTurn: StateFlow<List<GameSummary>?> = shownYourTurn.asStateFlow()
    val allRunning: StateFlow<List<GameSummary>?> = shownAllRunning.asStateFlow()

    /** The most recently finished games, newest first. null until the tab asks for them. */
    val finished: StateFlow<List<GameSummary>?> = _finished.asStateFlow()

    /** What the last [refresh] changed, for the screen to highlight. See [Arrivals]. */
    val arrivals: StateFlow<Arrivals> = _arrivals.asStateFlow()

    /** Brings the published lists in line with the servers' lists and [outgoing]. */
    private fun publish() {
        shownYourTurn.value = _yourTurn.value?.filterNot { it.id in outgoing }
        shownAllRunning.value =
            _allRunning.value?.map { if (it.id in outgoing) it.copy(isMyTurn = false) else it }
    }

    /** A move in [id] has been handed to the outbox: show the game as played from now on. */
    fun hold(id: GameId) {
        outgoing = outgoing + id
        publish()
    }

    /** The move in [id] did not go through: the game is the user's turn again. */
    fun release(id: GameId) {
        outgoing = outgoing - id
        publish()
    }

    /**
     * Fetches both lists from every signed-in server, all calls at once, and merges them into
     * one list per tab — the question the list answers is "what do I have to play", not
     * "what is on DGS".
     *
     * One server failing does not cost the other's games: a server that did not answer keeps
     * the games it had in the lists. Failures are returned, by server, for the screen to name;
     * nothing is thrown.
     */
    suspend fun refresh(): Map<ServerId, GoServerException> {
        val sessions = accounts.signedIn
        val answers =
            coroutineScope {
                sessions
                    .map { session ->
                        async {
                            session.serverId to
                                attempt {
                                    coroutineScope {
                                        val turn = async { session.call { it.gamesAwaitingMe() } }
                                        val running = async { session.call { it.myRunningGames() } }
                                        turn.await() to running.await()
                                    }
                                }
                        }
                    }.awaitAll()
            }
        val failures =
            answers
                .mapNotNull { (server, answer) ->
                    answer.exceptionOrNull()?.let {
                        server to
                            it as GoServerException
                    }
                }.toMap()
        // Nothing answered: the lists stay exactly as they were, unloaded ones included, so a
        // first load that failed still reads as "nothing to show" rather than "no games".
        if (sessions.isNotEmpty() && failures.size == sessions.size) return failures
        val fresh = answers.mapNotNull { (server, answer) -> answer.getOrNull()?.let { server to it } }.toMap()
        onYourTurnSeen(fresh.mapValues { it.value.first })
        // Games of a server that is signed in but did not answer stay as they were.
        val stale = failures.keys
        val turnList = merge(_yourTurn.value, stale, fresh.values.flatMap { it.first })
        val runningList = merge(_allRunning.value, stale, fresh.values.flatMap { it.second })
        // Worked out before the lists are replaced: what is new is only knowable
        // against what was there. Replaces the previous arrivals rather than adding to
        // them, so an old highlight never plays several refreshes late.
        _arrivals.value =
            Arrivals(
                yourTurn = arrivalsIn(_yourTurn.value, turnList),
                allRunning = arrivalsIn(_allRunning.value, runningList),
            )
        _yourTurn.value = turnList
        _allRunning.value = runningList
        publish()
        return failures
    }

    /** The fresh games plus the held ones of the servers in [keep], newest move first. */
    private fun merge(
        held: List<GameSummary>?,
        keep: Set<ServerId>,
        fresh: List<GameSummary>,
    ): List<GameSummary> = (held.orEmpty().filter { it.id.server in keep } + fresh).sortedByDescending { it.lastMoveAt }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: GoServerException) {
            Result.failure(e)
        }

    /**
     * Which of [now] are news since [before]: a game the list did not have, or one that has
     * advanced a move and is waiting on the user — the opponent having moved is the case
     * the highlight exists for. A game the user has just played in has advanced a move too,
     * but it is the opponent's turn afterwards, so it does not flash their own move back.
     *
     * Empty when [before] is null: nothing has been on screen yet, and a first load that
     * lit up every row at once would say nothing about any of them.
     */
    private fun arrivalsIn(
        before: List<GameSummary>?,
        now: List<GameSummary>,
    ): Set<GameId> {
        if (before == null) return emptySet()
        val known = before.associate { it.id to it.moveNumber }
        return now
            .filter { game ->
                val was = known[game.id]
                was == null || (game.moveNumber > was && game.isMyTurn)
            }.map { it.id }
            .toSet()
    }

    /** The highlight for [id] has played out; it must not play again on the next look. */
    fun onArrivalShown(id: GameId) {
        _arrivals.update { Arrivals(it.yourTurn - id, it.allRunning - id) }
    }

    /**
     * Fetches the last few finished games. Deliberately not part of [refresh]: it is a
     * request the user pays for only when they look at the archive.
     */
    suspend fun refreshFinished(): Map<ServerId, GoServerException> {
        val sessions = accounts.signedIn
        val answers =
            coroutineScope {
                sessions
                    .map { session ->
                        async { session.serverId to attempt { session.call { it.myFinishedGames(FINISHED_LIMIT) } } }
                    }.awaitAll()
            }
        val failures =
            answers
                .mapNotNull { (server, answer) ->
                    answer.exceptionOrNull()?.let {
                        server to
                            it as GoServerException
                    }
                }.toMap()
        if (sessions.isNotEmpty() && failures.size == sessions.size) return failures
        // The fifteen most recent across servers: the limit is the tab's, not each server's.
        _finished.value =
            merge(_finished.value, failures.keys, answers.flatMap { it.second.getOrNull().orEmpty() })
                .take(FINISHED_LIMIT)
        return failures
    }

    /** The record already held for [id], if one was fetched or prefetched earlier. */
    fun cachedDetail(id: GameId): GameDetail? = details[id]

    /**
     * [detail] with the user's clock as the games list last reported it. A held record is
     * reused for as long as the list agrees the game has not moved on, which on a slow game
     * is days — and the clock of the player to move runs all that while: a record cached at
     * 12 h left kept saying 12 h on the board long after the list said 3 h. The list is
     * fetched far more often, so while both describe the same move its clock is the fresher.
     * Only the user's own clock: the list carries no other.
     */
    fun withListClock(detail: GameDetail): GameDetail {
        val listed = summary(detail.summary.id) ?: return detail
        val time = listed.remainingTime
        if (listed.isFinished || listed.moveNumber != detail.summary.moveNumber || time == null) return detail
        if (time == detail.summary.remainingTime) return detail
        val info =
            detail.info?.let {
                if (detail.summary.myColor == StoneColor.BLACK) {
                    it.copy(black = it.black.copy(remainingTime = time))
                } else {
                    it.copy(white = it.white.copy(remainingTime = time))
                }
            }
        return detail.copy(summary = detail.summary.copy(remainingTime = time), info = info)
    }

    /** Remember a record just downloaded, so reopening the game costs nothing. */
    fun cache(detail: GameDetail) {
        val id = detail.summary.id
        details.remove(id)
        details[id] = detail
        while (details.size > MAX_CACHED_DETAILS) {
            details.remove(details.keys.first())
        }
    }

    /** The scoring state held for [id], if it is the one for the move [token] names. */
    fun cachedScore(
        id: GameId,
        token: MoveToken,
    ): HeldScore? = scores[id]?.takeIf { it.token == token }

    /**
     * Reads the scoring state of [detail], a game in its counting phase, and works out the
     * device's guess at the dead stones; kept for the next time the game is opened.
     *
     * The guess is made only when the server holds no marks and the user is the one to mark:
     * the opponent's marks are what the user is there to review, and a guess shown while
     * waiting would be marks nobody can send.
     */
    suspend fun readScore(detail: GameDetail): HeldScore {
        val id = detail.summary.id
        val token = detail.summary.moveToken
        val state = accounts.session(id.server).call { it.scoreState(id, token) }
        val guess =
            if (state.deadStones.isNotEmpty() || !detail.summary.isMyTurn) {
                emptyList()
            } else {
                val position = Replay(detail.record).finalPosition
                withContext(computeContext) { guessDeadStones(position) }
            }
        val score = HeldScore(token, state, guess)
        scores.remove(id)
        scores[id] = score
        while (scores.size > MAX_CACHED_DETAILS) {
            scores.remove(scores.keys.first())
        }
        return score
    }

    /**
     * Download [id]'s record in the background so opening it is instantaneous — and, for a
     * game being counted, its scoring state too, or the board would open without the marks
     * and switch to them a moment later. Skipped when a copy the games list agrees with is
     * already held, so a warm game is never fetched twice. Best effort throughout: prefetching is an optimisation, and a failure here must
     * never reach the user — the game simply loads normally when they open it.
     *
     * Deliberately narrow (the next game in the queue, and the first game waiting on the
     * user) rather than "download everything": DGS meters requests with a quota, and the
     * brief's bandwidth-consciousness argues against a silent bulk sync.
     */
    suspend fun prefetch(id: GameId) {
        val known = details[id]
        val summary = summary(id)
        val session = accounts.session(id.server)
        try {
            val detail =
                if (known != null && (summary == null || summary.moveNumber <= known.summary.moveNumber)) {
                    known
                } else {
                    session.call { it.game(id) }.also(::cache)
                }
            if (detail.summary.pendingAction != PendingAction.SCORE || !session.scoresInApp) return
            if (cachedScore(id, detail.summary.moveToken) == null) readScore(detail)
        } catch (e: GoServerException) {
            // Never surfaced: the game is fetched for real when it is opened.
        }
    }

    /**
     * Record that the user has just played their move in [id]. It leaves the "Your turn"
     * queue, but stays in the running list now on the opponent's turn, so the "All games"
     * tab keeps showing it (without the "your turn" marker) until the next refresh.
     */
    fun onMovePlayed(id: GameId) {
        // The cached record predates the move that was just sent, so it is no longer usable.
        details.remove(id)
        scores.remove(id)
        onArrivalShown(id)
        outgoing = outgoing - id
        _yourTurn.update { list -> list?.filterNot { it.id == id } }
        _allRunning.update { list ->
            list?.map { if (it.id == id) it.copy(isMyTurn = false) else it }
        }
        publish()
    }

    /**
     * The game is over — the user resigned it. It leaves both lists and the record cache,
     * so the queue steps past it and the list does not show it until the next refresh.
     */
    fun onGameEnded(id: GameId) {
        details.remove(id)
        scores.remove(id)
        onArrivalShown(id)
        _yourTurn.update { list -> list?.filterNot { it.id == id } }
        _allRunning.update { list -> list?.filterNot { it.id == id } }
        publish()
        // The game has just joined the archive, which this copy predates: drop it so the
        // Finished tab fetches the list again rather than showing one without this game.
        _finished.value = null
    }

    /**
     * The known summary for [id], if it is in either list. Lets the board screen draw the
     * header and an empty grid in their final position while the full record loads.
     */
    fun summary(id: GameId): GameSummary? =
        shownAllRunning.value?.firstOrNull { it.id == id }
            ?: shownYourTurn.value?.firstOrNull { it.id == id }
            ?: _finished.value?.firstOrNull { it.id == id }

    /**
     * The next game strictly after [id] within the list the user is browsing: all running
     * games when [all] is true, otherwise the "your turn" queue. Does not wrap: null when
     * [id] is the last game (or not in the list), which the UI reads as "back to the list".
     *
     * Reads the servers' list, so [id] is found even while its own move is on its way; the
     * games after it whose moves are on their way too are stepped over.
     */
    fun nextAfter(
        id: GameId,
        all: Boolean = false,
    ): GameId? {
        val list = (if (all) _allRunning else _yourTurn).value ?: return null
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        return list
            .drop(index + 1)
            .firstOrNull { all || it.id !in outgoing }
            ?.id
    }

    /**
     * Signs [session] out and takes its games out of every list; the other servers' games
     * stay. Otherwise the next account to sign in on this phone would briefly see the
     * previous one's games.
     */
    suspend fun signOut(session: SessionManager) {
        forget(session.serverId)
        session.signOut()
    }

    /** A server was signed out: its games leave every list, the others stay. */
    fun forget(server: ServerId) {
        details.keys.removeAll { it.server == server }
        scores.keys.removeAll { it.server == server }
        _yourTurn.update { list -> list?.filterNot { it.id.server == server } }
        _allRunning.update { list -> list?.filterNot { it.id.server == server } }
        _finished.update { list -> list?.filterNot { it.id.server == server } }
        publish()
    }

    fun clear() {
        _yourTurn.value = null
        _allRunning.value = null
        _finished.value = null
        _arrivals.value = Arrivals()
        details.clear()
        scores.clear()
        publish()
    }

    private companion object {
        /** Enough for the game in view plus the lookahead and a little history. */
        const val MAX_CACHED_DETAILS = 8

        /**
         * How many finished games the archive tab holds. A plain number, not "as many as the
         * screen fits": the count has to mean the same thing on every device, and a list that
         * scrolls a little is no worse than one that hides games already paid for. Fifteen is
         * a few weeks of correspondence play — enough to find the game you are thinking of,
         * far short of an archive to browse.
         */
        const val FINISHED_LIMIT = 15
    }
}
