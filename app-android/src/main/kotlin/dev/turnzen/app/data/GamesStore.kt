package dev.turnzen.app.data

import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The "your move" list shared between the games screen and the board screen's
 * auto-advance. Sorted with the most recent opponent move first.
 */
class GamesStore(
    private val session: SessionManager,
) {
    private val _games = MutableStateFlow<List<GameSummary>?>(null)

    /** null until the first successful load. */
    val games: StateFlow<List<GameSummary>?> = _games.asStateFlow()

    suspend fun refresh(): List<GameSummary> {
        val fresh = session.call { it.gamesAwaitingMe() }.sortedByDescending { it.lastMoveAt }
        _games.value = fresh
        return fresh
    }

    fun remove(id: GameId) {
        _games.update { list -> list?.filterNot { it.id == id } }
    }

    fun nextAfter(id: GameId): GameId? {
        val list = _games.value ?: return null
        val index = list.indexOfFirst { it.id == id }
        val remaining = if (index < 0) list else list.drop(index + 1) + list.take(index)
        return remaining.firstOrNull { it.id != id }?.id
    }

    fun clear() {
        _games.value = null
    }
}
