package dev.turnzen.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.ui.common.ServerProblem
import dev.turnzen.app.ui.common.problemFor
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

/** The three content filters on the Games screen. */
enum class GamesTab { YOUR_TURN, ALL, FINISHED }

/** How recent a refresh has to be for [GamesViewModel.refreshOnShow] to skip a new one. */
private val MIN_AUTO_REFRESH_NANOS = 15.seconds.inWholeNanoseconds

data class GamesUiState(
    /** Games where it is the user's move. null while the first load is in flight. */
    val yourTurn: List<GameSummary>? = null,
    /** Every running game. null while the first load is in flight. */
    val allRunning: List<GameSummary>? = null,
    /** The last finished games, newest first. null until the Finished tab has been opened. */
    val finished: List<GameSummary>? = null,
    val refreshing: Boolean = false,
    /** The last refresh's failure, if it failed. Null while the list is trusted. */
    val problem: ServerProblem? = null,
) {
    fun listFor(tab: GamesTab): List<GameSummary>? =
        when (tab) {
            GamesTab.YOUR_TURN -> yourTurn
            GamesTab.ALL -> allRunning
            GamesTab.FINISHED -> finished
        }
}

class GamesViewModel(
    private val session: SessionManager,
    private val store: GamesStore,
) : ViewModel() {
    private val transient = MutableStateFlow(GamesUiState())

    val state: StateFlow<GamesUiState> =
        combine(
            store.yourTurn,
            store.allRunning,
            store.finished,
            transient,
        ) { yourTurn, allRunning, finished, t ->
            t.copy(yourTurn = yourTurn, allRunning = allRunning, finished = finished)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            GamesUiState(
                yourTurn = store.yourTurn.value,
                allRunning = store.allRunning.value,
                finished = store.finished.value,
            ),
        )

    private var inFlight = false
    private var lastRefreshAt = 0L
    private var finishedInFlight = false

    /**
     * Pull to refresh: the user asked, so the spinner shows. On the archive that means the
     * archive only — the running lists are refreshed whenever the screen is shown, and the
     * two answers cost two requests.
     */
    fun refresh(tab: GamesTab = GamesTab.YOUR_TURN) {
        if (tab == GamesTab.FINISHED) loadFinished(silent = false) else start(silent = false)
    }

    /**
     * The user switched to a tab. Only the archive has anything to do here: it is fetched
     * the first time it is looked at, and then left alone, because a finished game does not
     * change. [GamesStore.onGameEnded] drops the list when a game joins the archive, which
     * is what makes the next visit fetch it again.
     */
    fun onTabShown(tab: GamesTab) {
        if (tab == GamesTab.FINISHED && store.finished.value == null) loadFinished(silent = true)
    }

    private fun loadFinished(silent: Boolean) {
        if (finishedInFlight) return
        finishedInFlight = true
        if (!silent) transient.update { it.copy(refreshing = true, problem = null) }
        viewModelScope.launch {
            try {
                store.refreshFinished()
                transient.update { it.copy(refreshing = false, problem = null) }
            } catch (e: GoServerException) {
                transient.update { it.copy(refreshing = false, problem = problemFor(e, session.serverName)) }
            } finally {
                finishedInFlight = false
            }
        }
    }

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
        if (!silent) transient.update { it.copy(refreshing = true, problem = null) }
        viewModelScope.launch {
            try {
                store.refresh()
                lastRefreshAt = System.nanoTime()
                transient.update { it.copy(refreshing = false, problem = null) }
                warmFirstGame()
            } catch (e: GoServerException) {
                // Shown for a silent refresh too: it explains why the list looks unchanged.
                transient.update { it.copy(refreshing = false, problem = problemFor(e, session.serverName)) }
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * Download the record of the game at the top of "Your turn" once the list lands, so the
     * first game the user opens is already there. One game only: the rest of the queue is
     * warmed a game at a time from the board itself.
     */
    private fun warmFirstGame() {
        val first =
            store.yourTurn.value
                ?.firstOrNull()
                ?.id ?: return
        viewModelScope.launch { store.prefetch(first) }
    }
}
