package dev.turnzen.app.data

import dev.turnzen.game.Move
import dev.turnzen.game.TrialVariation
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import kotlinx.coroutines.CoroutineScope
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
 * A move that came back to the board instead of reaching the game: refused or undelivered by
 * the server. [movedOn] is the case where the game changed under it, so the line it came from
 * answers a position that no longer exists.
 */
data class ReturnedMove(
    val move: OutgoingMove,
    val error: GoServerException,
    val movedOn: Boolean = false,
)

/**
 * Where a sent move goes to the server, and where it comes back from if it does not get there.
 *
 * Send has no confirmation and no delay: the button already names the move, and it sits at the
 * bottom of the screen, away from the board. The board steps on to the next game at once and
 * the move is transmitted from here, straight away, while the app is still on screen.
 *
 * Lives as long as the app, not the board, because the board it came from is gone by the time
 * the server answers — and a failure has to be reported wherever the user is by then.
 *
 * (Until October 2026 a move first waited four seconds with an "Undo" on screen. Leaving the
 * app in those seconds sent it just as Android froze or cut off the backgrounded process, so it
 * often failed and came back; sending at once leaves no such window.)
 */
class MoveOutbox(
    private val accounts: Accounts,
    private val games: GamesStore,
    private val scope: CoroutineScope,
) {
    private val _failed = MutableStateFlow<ReturnedMove?>(null)
    private val inFlight = MutableStateFlow<Set<GameId>>(emptySet())
    private val returned = HashMap<GameId, ReturnedMove>()

    /** The newest move that did not go through, until the user opens its game. */
    val failed: StateFlow<ReturnedMove?> = _failed.asStateFlow()

    fun submit(move: OutgoingMove) {
        val id = move.gameId
        games.hold(id)
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

    /** True while [id]'s move is on the wire: the game is neither played nor not. */
    fun isOutgoing(id: GameId): Boolean = id in inFlight.value

    /** Suspends until [id]'s move has reached the server or come back. */
    suspend fun awaitDelivery(id: GameId) {
        inFlight.first { id !in it }
    }

    /** The move that came back to [id]'s board, handed over once; clears its line if shown. */
    fun claim(id: GameId): ReturnedMove? {
        _failed.update { if (it?.move?.gameId == id) null else it }
        return returned.remove(id)
    }

    companion object {
        /** DGS's code for a move sent when it is the opponent's turn. */
        const val CODE_NOT_YOUR_TURN = "not_your_turn"
    }
}
