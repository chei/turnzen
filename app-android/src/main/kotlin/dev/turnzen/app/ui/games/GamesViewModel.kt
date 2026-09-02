package dev.turnzen.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GamesUiState(
    /** null while the first load is in flight. */
    val games: List<GameSummary>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class GamesViewModel(
    private val session: SessionManager,
    private val store: GamesStore,
) : ViewModel() {
    private val transient = MutableStateFlow(GamesUiState())

    val state: StateFlow<GamesUiState> =
        combine(store.games, transient) { games, t -> t.copy(games = games) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamesUiState(games = store.games.value))

    init {
        refresh()
    }

    fun refresh() {
        if (transient.value.refreshing) return
        transient.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                store.refresh()
                transient.update { it.copy(refreshing = false) }
            } catch (e: GoServerException) {
                transient.update { it.copy(refreshing = false, error = messageFor(e)) }
            }
        }
    }

    private fun messageFor(e: GoServerException): String {
        val server = session.serverName
        return when (e) {
            is GoServerException.Unreachable -> "Couldn't reach $server. Check your connection and pull down to retry."
            is GoServerException.RateLimited -> "$server asked us to slow down. Try again in a few minutes."
            is GoServerException.SessionExpired -> "Signed out by $server. Sign in again."
            is GoServerException.InvalidCredentials -> "Signed out by $server. Sign in again."
            else -> "Couldn't load games: ${e.message}"
        }
    }
}
