package dev.turnzen.app.data

import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
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

    /**
     * Record that the user has just played their move in [id]. It leaves the "Your turn"
     * queue, but stays in the running list now on the opponent's turn, so the "All games"
     * tab keeps showing it (without the "your turn" marker) until the next refresh.
     */
    fun onMovePlayed(id: GameId) {
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
    }
}
