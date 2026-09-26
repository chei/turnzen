package dev.turnzen.app.data

import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the last refresh brought into each running list: the rows the Games screen has not
 * yet drawn attention to. Consumed one row at a time, as each highlight plays out.
 */
data class Arrivals(
    val yourTurn: Set<GameId> = emptySet(),
    val allRunning: Set<GameId> = emptySet(),
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
    /**
     * Told what each answering server said is awaiting the user, after every [refresh]: the
     * user has now seen those games, so the notifications need not announce them.
     */
    private val onYourTurnSeen: (Map<ServerId, List<GameSummary>>) -> Unit = {},
) {
    private val _yourTurn = MutableStateFlow<List<GameSummary>?>(null)
    private val _allRunning = MutableStateFlow<List<GameSummary>?>(null)
    private val _finished = MutableStateFlow<List<GameSummary>?>(null)
    private val _arrivals = MutableStateFlow(Arrivals())

    /**
     * Full records fetched ahead of time, so opening those games needs no download. Kept
     * small and in insertion order: the oldest entry is dropped once the cap is reached.
     * Only ever touched from the main dispatcher (view-model scopes), so a plain map is fine.
     */
    private val details = LinkedHashMap<GameId, GameDetail>()

    /** null until the first successful load. */
    val yourTurn: StateFlow<List<GameSummary>?> = _yourTurn.asStateFlow()
    val allRunning: StateFlow<List<GameSummary>?> = _allRunning.asStateFlow()

    /** The most recently finished games, newest first. null until the tab asks for them. */
    val finished: StateFlow<List<GameSummary>?> = _finished.asStateFlow()

    /** What the last [refresh] changed, for the screen to highlight. See [Arrivals]. */
    val arrivals: StateFlow<Arrivals> = _arrivals.asStateFlow()

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

    /** Remember a record just downloaded, so reopening the game costs nothing. */
    fun cache(detail: GameDetail) {
        val id = detail.summary.id
        details.remove(id)
        details[id] = detail
        while (details.size > MAX_CACHED_DETAILS) {
            details.remove(details.keys.first())
        }
    }

    /**
     * Download [id]'s record in the background so opening it is instantaneous. Skipped when
     * a copy the games list agrees with is already held, so a warm game is never fetched
     * twice. Best effort throughout: prefetching is an optimisation, and a failure here must
     * never reach the user — the game simply loads normally when they open it.
     *
     * Deliberately narrow (the next game in the queue, and the first game waiting on the
     * user) rather than "download everything": DGS meters requests with a quota, and the
     * brief's bandwidth-consciousness argues against a silent bulk sync.
     */
    suspend fun prefetch(id: GameId) {
        val known = details[id]
        val summary = summary(id)
        if (known != null && (summary == null || summary.moveNumber <= known.summary.moveNumber)) return
        try {
            cache(accounts.session(id.server).call { it.game(id) })
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
        onArrivalShown(id)
        _yourTurn.update { list -> list?.filterNot { it.id == id } }
        _allRunning.update { list ->
            list?.map { if (it.id == id) it.copy(isMyTurn = false) else it }
        }
    }

    /**
     * The game is over — the user resigned it. It leaves both lists and the record cache,
     * so the queue steps past it and the list does not show it until the next refresh.
     */
    fun onGameEnded(id: GameId) {
        details.remove(id)
        onArrivalShown(id)
        _yourTurn.update { list -> list?.filterNot { it.id == id } }
        _allRunning.update { list -> list?.filterNot { it.id == id } }
        // The game has just joined the archive, which this copy predates: drop it so the
        // Finished tab fetches the list again rather than showing one without this game.
        _finished.value = null
    }

    /**
     * The known summary for [id], if it is in either list. Lets the board screen draw the
     * header and an empty grid in their final position while the full record loads.
     */
    fun summary(id: GameId): GameSummary? =
        _allRunning.value?.firstOrNull { it.id == id }
            ?: _yourTurn.value?.firstOrNull { it.id == id }
            ?: _finished.value?.firstOrNull { it.id == id }

    /**
     * The next game strictly after [id] within the list the user is browsing: all running
     * games when [all] is true, otherwise the "your turn" queue. Does not wrap: null when
     * [id] is the last game (or not in the list), which the UI reads as "back to the list".
     */
    fun nextAfter(
        id: GameId,
        all: Boolean = false,
    ): GameId? {
        val list = (if (all) _allRunning else _yourTurn).value ?: return null
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        return list.drop(index + 1).firstOrNull()?.id
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
        _yourTurn.update { list -> list?.filterNot { it.id.server == server } }
        _allRunning.update { list -> list?.filterNot { it.id.server == server } }
        _finished.update { list -> list?.filterNot { it.id.server == server } }
    }

    fun clear() {
        _yourTurn.value = null
        _allRunning.value = null
        _finished.value = null
        _arrivals.value = Arrivals()
        details.clear()
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
