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
import kotlin.time.Duration.Companion.seconds

/** The two content filters on the Games screen. */
enum class GamesTab { YOUR_TURN, ALL }

/** How recent a refresh has to be for [GamesViewModel.refreshOnShow] to skip a new one. */
private val MIN_AUTO_REFRESH_NANOS = 15.seconds.inWholeNanoseconds

data class GamesUiState(
    /** Games where it is the user's move. null while the first load is in flight. */
    val yourTurn: List<GameSummary>? = null,
    /** Every running game. null while the first load is in flight. */
    val allRunning: List<GameSummary>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    fun listFor(tab: GamesTab): List<GameSummary>? = if (tab == GamesTab.YOUR_TURN) yourTurn else allRunning
}

class GamesViewModel(
    private val session: SessionManager,
    private val store: GamesStore,
) : ViewModel() {
    private val transient = MutableStateFlow(GamesUiState())

    val state: StateFlow<GamesUiState> =
        combine(store.yourTurn, store.allRunning, transient) { yourTurn, allRunning, t ->
            t.copy(yourTurn = yourTurn, allRunning = allRunning)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            GamesUiState(yourTurn = store.yourTurn.value, allRunning = store.allRunning.value),
        )

    private var inFlight = false
    private var lastRefreshAt = 0L

    /** Pull to refresh: the user asked, so the spinner shows. */
    fun refresh() = start(silent = false)

    /**
     * Called whenever the list comes back into view — app start, returning from a game, or
     * resuming the app — so it is never the list from the last time it was opened.
     *
     * Silent: the games already on screen stay put and no spinner appears; rows update when
     * the answer lands. Skipped when a refresh finished moments ago, so walking through a
     * queue of games does not re-ask the server on every step back to the list.
     */
    fun refreshOnShow() {
        if (System.nanoTime() - lastRefreshAt < MIN_AUTO_REFRESH_NANOS) return
        start(silent = true)
    }

    private fun start(silent: Boolean) {
        if (inFlight) return
        inFlight = true
        if (!silent) transient.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                store.refresh()
                lastRefreshAt = System.nanoTime()
                transient.update { it.copy(refreshing = false, error = null) }
            } catch (e: GoServerException) {
                // Shown for a silent refresh too: it explains why the list looks unchanged.
                transient.update { it.copy(refreshing = false, error = messageFor(e)) }
            } finally {
                inFlight = false
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
