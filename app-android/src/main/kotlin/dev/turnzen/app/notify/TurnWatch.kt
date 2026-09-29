package dev.turnzen.app.notify

import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.ServerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What the notifications remember between checks. Small, and stored as it is (see
 * [WatchStateStore]); every map is keyed by game and holds the move number it was taken at,
 * so the same game coming round again after two more moves counts as new.
 */
data class WatchState(
    /** The user's-move games already accounted for: alerted about, or seen in the app. */
    val known: Map<GameId, Int> = emptyMap(),
    /** The games of the latest alerts the user has not yet seen; marked "new" in the summary. */
    val flagged: Set<GameId> = emptySet(),
    /** Games whose "time running low" has been said, for the move it was said on. */
    val lowAlerted: Map<GameId, Int> = emptyMap(),
    /** Servers whose sign-out has been notified; forgotten once signed in again. */
    val signedOutNoticed: Set<ServerId> = emptySet(),
)

/** One check's view of the servers. */
data class CheckInput(
    /** The user's-move games of every server that answered. */
    val answered: Map<ServerId, List<GameSummary>>,
    /** Signed-in servers that could not be asked this time (offline, server trouble). */
    val failed: Set<ServerId> = emptySet(),
    /** Servers that signed the user out and could not be signed in to again silently. */
    val signedOut: Set<ServerId> = emptySet(),
    val yourMoveOn: Boolean,
    val lowTimeOn: Boolean,
    /** The app is on screen: nothing is posted, and what the check saw counts as seen. */
    val inForeground: Boolean,
    /** Whether the summary notification is up right now. */
    val summaryShown: Boolean,
    val now: Instant,
)

sealed interface SummaryAction {
    /** Leave it as it is. */
    data object Keep : SummaryAction

    data object Cancel : SummaryAction

    /** Post or replace it; [alert] says whether it sounds. */
    data class Post(
        val games: List<GameSummary>,
        val flagged: Set<GameId>,
        val alert: Boolean,
    ) : SummaryAction
}

data class CheckOutcome(
    val state: WatchState,
    val summary: SummaryAction,
    val lowTimeAlerts: List<GameSummary>,
    /** Low-time notices to take down: those games are no longer the user's move. */
    val lowTimeCancels: Set<GameId>,
    val signedOutAlerts: Set<ServerId>,
    /** When the next game's clock will cross [TurnWatch.LOW_TIME]; null when none will. */
    val nextLowTimeCheck: Instant?,
)

/**
 * Decides what the notifications say after a check. Plain Kotlin with no Android in it: the
 * worker gathers the games, hands them here, and posts what comes back.
 */
object TurnWatch {
    /** "Time running low" once the user's main time is at most this. */
    val LOW_TIME: Duration = 24.hours

    /** The shortest wait for the next low-time check, so a paused clock does not spin. */
    val MIN_LOW_TIME_WAIT: Duration = 15.minutes

    fun check(
        state: WatchState,
        input: CheckInput,
    ): CheckOutcome {
        val answeredServers = input.answered.keys
        val current =
            input.answered.values
                .flatten()
                .filter { it.isMyTurn && !it.isFinished }
        val currentIds = current.map { it.id }.toSet()

        // New: a game not accounted for at this move. Only answering servers can have any.
        val fresh = current.filter { state.known[it.id] != it.moveNumber }
        val known = keepOthers(state.known, answeredServers) + current.associate { it.id to it.moveNumber }

        val low =
            if (input.lowTimeOn) {
                current.filter { isLow(it) && state.lowAlerted[it.id] != it.moveNumber }
            } else {
                emptyList()
            }
        // A game that is no longer the user's move is forgotten: it can warn again next time.
        val lowAlerted =
            state.lowAlerted.filterKeys { it.server !in answeredServers || it in currentIds } +
                low.associate { it.id to it.moveNumber }
        val lowTimeCancels =
            state.lowAlerted.keys
                .filter { it.server in answeredServers && it !in currentIds }
                .toSet()

        val signedOutAlerts = input.signedOut - state.signedOutNoticed
        val stillSignedIn = answeredServers + input.failed
        val signedOutNoticed = (state.signedOutNoticed + input.signedOut) - stillSignedIn

        val nextLowTimeCheck =
            if (input.lowTimeOn) {
                // Only games still above the line have a crossing ahead of them.
                current
                    .mapNotNull { game -> game.remainingTime?.main?.minus(LOW_TIME) }
                    .filter { it.isPositive() }
                    .minOrNull()
                    ?.let { input.now + maxOf(it, MIN_LOW_TIME_WAIT) }
            } else {
                null
            }

        if (input.inForeground) {
            // On screen: everything the check saw is seen, so nothing alerts about it later.
            return CheckOutcome(
                state = WatchState(known, emptySet(), lowAlerted, signedOutNoticed),
                summary = SummaryAction.Cancel,
                lowTimeAlerts = emptyList(),
                lowTimeCancels = lowTimeCancels,
                signedOutAlerts = emptySet(),
                nextLowTimeCheck = nextLowTimeCheck,
            )
        }

        val flagged = (state.flagged + fresh.map { it.id }) intersect currentIds
        val summary =
            when {
                !input.yourMoveOn -> SummaryAction.Cancel

                fresh.isNotEmpty() -> SummaryAction.Post(order(current, flagged), flagged, alert = true)

                // A server that did not answer would lose its games from the list: wait.
                input.failed.isNotEmpty() -> SummaryAction.Keep

                current.isEmpty() -> SummaryAction.Cancel

                input.summaryShown -> SummaryAction.Post(order(current, flagged), flagged, alert = false)

                else -> SummaryAction.Keep
            }
        return CheckOutcome(
            state = WatchState(known, if (input.yourMoveOn) flagged else emptySet(), lowAlerted, signedOutNoticed),
            summary = summary,
            lowTimeAlerts = low,
            lowTimeCancels = lowTimeCancels,
            signedOutAlerts = signedOutAlerts,
            nextLowTimeCheck = nextLowTimeCheck,
        )
    }

    /**
     * The app has been opened: every notification goes, and the games the user can now see
     * count as seen. [answered] is what the app itself fetched, per server, when it has.
     */
    fun seen(
        state: WatchState,
        answered: Map<ServerId, List<GameSummary>> = emptyMap(),
    ): WatchState {
        val current = answered.values.flatten().filter { it.isMyTurn && !it.isFinished }
        val lowNow = current.filter(::isLow)
        return state.copy(
            known = keepOthers(state.known, answered.keys) + current.associate { it.id to it.moveNumber },
            flagged = emptySet(),
            lowAlerted = state.lowAlerted + lowNow.associate { it.id to it.moveNumber },
        )
    }

    /** Main time at most [LOW_TIME]. A clock the app could not read is never low. */
    fun isLow(game: GameSummary): Boolean {
        val main = game.remainingTime?.main ?: return false
        return main <= LOW_TIME
    }

    /** The flagged games first, each part in the order the servers gave, most recent move first. */
    private fun order(
        games: List<GameSummary>,
        flagged: Set<GameId>,
    ): List<GameSummary> =
        games
            .sortedByDescending { it.lastMoveAt }
            .sortedByDescending { it.id in flagged }

    private fun <V> keepOthers(
        map: Map<GameId, V>,
        servers: Set<ServerId>,
    ): Map<GameId, V> = map.filterKeys { it.server !in servers }
}
