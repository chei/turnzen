package dev.turnzen.app.data

import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The game lists shown on the Games screen and used by the board screen's auto-advance.
 *
 * Two lists, both sorted with the most recent move first:
 * - [yourTurn]: games where it is the user's move (the "Your turn" tab, and the queue the
 *   board screen advances through after a Send).
 * - [allRunning]: every running game, whichever side is to move (the "All games" tab).
 */
class GamesStore(
    private val session: SessionManager,
) {
    private val _yourTurn = MutableStateFlow<List<GameSummary>?>(null)
    private val _allRunning = MutableStateFlow<List<GameSummary>?>(null)

    /**
     * Full records fetched ahead of time, so opening those games needs no download. Kept
     * small and in insertion order: the oldest entry is dropped once the cap is reached.
     * Only ever touched from the main dispatcher (view-model scopes), so a plain map is fine.
     */
    private val details = LinkedHashMap<GameId, GameDetail>()

    /** null until the first successful load. */
    val yourTurn: StateFlow<List<GameSummary>?> = _yourTurn.asStateFlow()
    val allRunning: StateFlow<List<GameSummary>?> = _allRunning.asStateFlow()

    /** Fetches both lists. The two server calls run concurrently. */
    suspend fun refresh() {
        coroutineScope {
            val turn = async { session.call { it.gamesAwaitingMe() } }
            val running = async { session.call { it.myRunningGames() } }
            _yourTurn.value = turn.await().sortedByDescending { it.lastMoveAt }
            _allRunning.value = running.await().sortedByDescending { it.lastMoveAt }
        }
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
            cache(session.call { it.game(id) })
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
        _yourTurn.update { list -> list?.filterNot { it.id == id } }
        _allRunning.update { list ->
            list?.map { if (it.id == id) it.copy(isMyTurn = false) else it }
        }
    }

    /**
     * The known summary for [id], if it is in either list. Lets the board screen draw the
     * header and an empty grid in their final position while the full record loads.
     */
    fun summary(id: GameId): GameSummary? =
        _allRunning.value?.firstOrNull { it.id == id }
            ?: _yourTurn.value?.firstOrNull { it.id == id }

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

    fun clear() {
        _yourTurn.value = null
        _allRunning.value = null
        details.clear()
    }

    private companion object {
        /** Enough for the game in view plus the lookahead and a little history. */
        const val MAX_CACHED_DETAILS = 8
    }
}
