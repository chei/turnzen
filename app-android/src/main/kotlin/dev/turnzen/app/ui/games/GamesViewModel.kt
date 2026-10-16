package dev.turnzen.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.Arrivals
import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.ui.common.ServerProblem
import dev.turnzen.app.ui.common.problemFor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    /** The rows the last refresh brought in, for the screen to highlight once. */
    val arrivals: Arrivals = Arrivals(),
    val refreshing: Boolean = false,
    /**
     * What went wrong in the last refresh, one entry per server that failed, plus one per
     * server whose session ended and needs the user to sign in again. Empty while every list
     * is trusted.
     */
    val problems: List<ServerProblem> = emptyList(),
    /** Sign-in problems of individual servers, kept apart from [problems] because they outlive a refresh. */
    val signedOut: List<ServerProblem> = emptyList(),
) {
    /** The first problem: the one a screen with nothing else to show puts in the middle. */
    val problem: ServerProblem?
        get() = (problems + signedOut).firstOrNull()

    /** Every problem, for the quiet lines above a list that is on screen. */
    val allProblems: List<ServerProblem>
        get() = problems + signedOut

    fun listFor(tab: GamesTab): List<GameSummary>? =
        when (tab) {
            GamesTab.YOUR_TURN -> yourTurn
            GamesTab.ALL -> allRunning
            GamesTab.FINISHED -> finished
        }

    /**
     * The games to highlight on [tab]. Never the archive: it is fetched from scratch the
     * first time it is opened, so every row in it would count as new.
     */
    fun arrivalsFor(tab: GamesTab): Set<GameId> =
        when (tab) {
            GamesTab.YOUR_TURN -> arrivals.yourTurn
            GamesTab.ALL -> arrivals.allRunning
            GamesTab.FINISHED -> emptySet()
        }
}

class GamesViewModel(
    private val accounts: Accounts,
    private val store: GamesStore,
) : ViewModel() {
    private val transient = MutableStateFlow(GamesUiState())

    val state: StateFlow<GamesUiState> =
        combine(
            combine(store.yourTurn, store.allRunning, store.finished, ::Triple),
            store.arrivals,
            accounts.states,
            transient,
        ) { (yourTurn, allRunning, finished), arrivals, auth, t ->
            t.copy(
                yourTurn = yourTurn,
                allRunning = allRunning,
                finished = finished,
                arrivals = arrivals,
                signedOut = signedOutLines(auth),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            GamesUiState(
                yourTurn = store.yourTurn.value,
                allRunning = store.allRunning.value,
                finished = store.finished.value,
                arrivals = store.arrivals.value,
            ),
        )

    private var inFlight = false
    private var lastRefreshAt = 0L
    private var finishedInFlight = false

    /**
     * Whether the list is on screen. A refresh can still be answered after the user has left
     * the app, and Android cuts a backgrounded app off the network, so that answer is often
     * "can't reach" for every server. Nobody saw it happen and it says nothing about the
     * network the user comes back to; kept, it would flash above the list on return until
     * the refresh made then landed.
     */
    private var onScreen = true

    init {
        // A server signed in or out (from Settings, say) changes what the lists should hold:
        // fetch at once rather than wait for the next time the list is shown.
        viewModelScope.launch {
            accounts.states
                .map { states ->
                    accounts.sessions
                        .zip(
                            states,
                        ).filter { it.second is AuthState.SignedIn }
                        .map { it.first.serverId }
                        .toSet()
                }.distinctUntilChanged()
                .drop(1)
                .collect {
                    lastRefreshAt = 0L
                    start(silent = true)
                    if (store.finished.value != null) loadFinished(silent = true)
                }
        }
    }

    /**
     * Pull to refresh: the user asked, so the spinner shows. On the archive that means the
     * archive only — the running lists are refreshed whenever the screen is shown, and the
     * two answers cost two requests.
     */
    fun refresh(tab: GamesTab = GamesTab.YOUR_TURN) {
        if (tab == GamesTab.FINISHED) loadFinished(silent = false) else start(silent = false)
    }

    /** A row has finished its highlight, so it is no longer new. */
    fun onArrivalShown(id: GameId) = store.onArrivalShown(id)

    /** The name of the server [id] is on, for what the screen says about it. */
    fun serverName(id: GameId): String = accounts.session(id.server).serverName

    /** The web page of a game Turnzen lists but cannot show ([GameSummary.onlyOnWeb]). */
    fun gameUrl(id: GameId): String? = accounts.session(id.server).gameUrl(id)

    /**
     * A server that signed the user out while another one stays signed in: one line in the
     * list, not a trip back to the sign-in screen (the other server's games are still here).
     */
    private fun signedOutLines(states: List<AuthState>): List<ServerProblem> =
        accounts.sessions.zip(states).mapNotNull { (session, state) ->
            val reason = (state as? AuthState.SignedOut)?.reason ?: return@mapNotNull null
            ServerProblem(title = "Signed out", message = reason, transient = false, server = session.serverId)
        }

    private fun problemsFor(failures: Map<ServerId, GoServerException>): List<ServerProblem> =
        failures.map { (server, e) -> problemFor(e, accounts.session(server).serverName).copy(server = server) }

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
        if (!silent) transient.update { it.copy(refreshing = true, problems = emptyList()) }
        viewModelScope.launch {
            try {
                val failures = store.refreshFinished()
                transient.update { it.copy(refreshing = false, problems = shownProblems(failures)) }
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
     *
     * A refresh made here takes the problem lines down first: they are about an earlier
     * attempt, often hours old, and the network the user comes back to is usually a
     * different one. If the server still cannot be reached, the new attempt puts them back.
     */
    fun refreshOnShow() {
        onScreen = true
        if (System.nanoTime() - lastRefreshAt < MIN_AUTO_REFRESH_NANOS) return
        transient.update { it.copy(problems = emptyList()) }
        start(silent = true)
    }

    /** The list went out of view: the app was left, or a game opened over it. */
    fun onHidden() {
        onScreen = false
    }

    private fun shownProblems(failures: Map<ServerId, GoServerException>): List<ServerProblem> =
        if (onScreen) problemsFor(failures) else emptyList()

    private fun start(silent: Boolean) {
        if (inFlight) return
        inFlight = true
        if (!silent) transient.update { it.copy(refreshing = true, problems = emptyList()) }
        viewModelScope.launch {
            try {
                // Failures are shown for a silent refresh too: they explain why the list, or
                // one server's part of it, looks unchanged.
                val failures = store.refresh()
                if (failures.size < accounts.signedIn.size) lastRefreshAt = System.nanoTime()
                transient.update { it.copy(refreshing = false, problems = shownProblems(failures)) }
                warmFirstGame()
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
