package dev.turnzen.app.data

import dev.turnzen.game.Move
import dev.turnzen.game.TrialVariation
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A move the user has sent: what goes to the server, and what to put back if it returns. */
data class OutgoingMove(
    val gameId: GameId,
    val token: MoveToken,
    val move: Move,
    val comment: String?,
    /** "D4", or null for a pass: how the app-wide line names the move. */
    val label: String?,
    val opponent: String,
    /** The line on the board when it was sent, restored if the move comes back. */
    val trial: TrialVariation?,
)

/**
 * A move that came back to the board instead of reaching the game: undone by the user
 * ([error] null), or refused or undelivered by the server. [movedOn] is the case where the
 * game changed under it, so the line it came from answers a position that no longer exists.
 */
data class ReturnedMove(
    val move: OutgoingMove,
    val error: GoServerException? = null,
    val movedOn: Boolean = false,
)

/**
 * Where a sent move waits out its undo window and then goes to the server.
 *
 * Send replaces the old confirmation dialog with a chance to change one's mind afterwards:
 * the board steps on to the next game at once, and the move stays here for [undoWindowMillis]
 * with an "Undo" on screen before it is transmitted. An undo that nobody reads past is cheaper
 * than a dialog everyone taps through.
 *
 * Lives as long as the app, not the board, because the board it came from is gone by the time
 * the move is sent — and a failure has to be reported wherever the user is by then.
 *
 * Only one move is ever inside its window: sending another commits the one before it, since
 * the undo on screen can only be about the newest. [flush] commits it too, when the app leaves
 * the screen — a move the user saw leave must not wait on a process Android may kill.
 */
class MoveOutbox(
    private val accounts: Accounts,
    private val games: GamesStore,
    private val scope: CoroutineScope,
    private val undoWindowMillis: Long = UNDO_WINDOW_MILLIS,
) {
    private val _waiting = MutableStateFlow<OutgoingMove?>(null)
    private val _failed = MutableStateFlow<ReturnedMove?>(null)
    private val inFlight = MutableStateFlow<Set<GameId>>(emptySet())
    private val returned = HashMap<GameId, ReturnedMove>()
    private var timer: Job? = null

    /** The move inside its undo window, for the line that offers "Undo". */
    val waiting: StateFlow<OutgoingMove?> = _waiting.asStateFlow()

    /** The newest move that did not go through, until the user opens its game. */
    val failed: StateFlow<ReturnedMove?> = _failed.asStateFlow()

    fun submit(move: OutgoingMove) {
        flush()
        games.hold(move.gameId)
        _waiting.value = move
        timer =
            scope.launch {
                delay(undoWindowMillis)
                commit(move)
            }
    }

    /** Takes the waiting move back. It is returned to its board the next time that opens. */
    fun undo(): OutgoingMove? {
        val move = _waiting.value ?: return null
        timer?.cancel()
        _waiting.value = null
        games.release(move.gameId)
        returned[move.gameId] = ReturnedMove(move)
        return move
    }

    /** Sends the waiting move now rather than at the end of its window. */
    fun flush() {
        val move = _waiting.value ?: return
        timer?.cancel()
        commit(move)
    }

    /** True while [id]'s move is waiting or on the wire: the game is neither played nor not. */
    fun isOutgoing(id: GameId): Boolean = _waiting.value?.gameId == id || id in inFlight.value

    /** Suspends until [id]'s move has reached the server or come back; sends a waiting one now. */
    suspend fun awaitDelivery(id: GameId) {
        if (_waiting.value?.gameId == id) flush()
        inFlight.first { id !in it }
    }

    /** The move that came back to [id]'s board, handed over once; clears its line if shown. */
    fun claim(id: GameId): ReturnedMove? {
        _failed.update { if (it?.move?.gameId == id) null else it }
        return returned.remove(id)
    }

    private fun commit(move: OutgoingMove) {
        val id = move.gameId
        if (_waiting.value == move) _waiting.value = null
        inFlight.update { it + id }
        scope.launch {
            try {
                accounts.session(id.server).call { it.sendMove(id, move.token, move.move, move.comment) }
                games.onMovePlayed(id)
            } catch (e: GoServerException) {
                // "Not your turn" is not a bad move: the game moved on without us (the
                // opponent played, or this player moved in another client), exactly as when
                // the move token is stale.
                val movedOn =
                    e is GoServerException.StaleGame ||
                        (e is GoServerException.MoveRejected && e.code == CODE_NOT_YOUR_TURN)
                val back = ReturnedMove(move, e, movedOn)
                returned[id] = back
                _failed.value = back
                games.release(id)
                if (movedOn) {
                    // The same event usually changed whose turn it is elsewhere too.
                    games.refresh()
                }
            } finally {
                inFlight.update { it - id }
            }
        }
    }

    companion object {
        /** Long enough to notice a slip and reach Undo, short enough not to hold a move up. */
        const val UNDO_WINDOW_MILLIS = 4_000L

        /** DGS's code for a move sent when it is the opponent's turn. */
        const val CODE_NOT_YOUR_TURN = "not_your_turn"
    }
}
