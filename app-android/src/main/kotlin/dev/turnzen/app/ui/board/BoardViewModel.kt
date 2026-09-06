package dev.turnzen.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Coordinates
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.Position
import dev.turnzen.game.Replay
import dev.turnzen.game.TrialOutcome
import dev.turnzen.game.TrialVariation
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PendingConfirmation { SEND, PASS }

data class BoardUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: GameDetail? = null,
    val replay: Replay? = null,
    /** Which server move is displayed while browsing history (0..moveCount). */
    val viewingMove: Int = 0,
    val trial: TrialVariation? = null,
    val sending: Boolean = false,
    val confirmation: PendingConfirmation? = null,
    /** Short-lived feedback ("Sent", "The game changed on the server"). */
    val notice: String? = null,
) {
    val moveCount: Int
        get() = replay?.moveCount ?: 0

    /** False when the game is open only to explore the opponent's move; no move can be sent. */
    val isMyTurn: Boolean
        get() = detail?.summary?.isMyTurn ?: false

    val isTrial: Boolean
        get() = trial != null && !trial.isEmpty

    val atLatest: Boolean
        get() = viewingMove == moveCount

    /** What the board shows right now. */
    val position: Position?
        get() = if (isTrial) trial?.position else replay?.positionAfter(viewingMove)

    val trialMoves: List<ColoredMove>
        get() = trial?.moves ?: emptyList()

    /** The move Send would transmit: the first trial stone. */
    val moveToSend: ColoredMove?
        get() = trial?.firstMove

    val sendLabel: String?
        get() {
            val move = moveToSend ?: return null
            val size = detail?.record?.boardSize ?: return null
            return when (val m = move.move) {
                Move.Pass -> "Pass"
                is Move.Play -> Coordinates.label(m.point, size)
            }
        }

    val canSend: Boolean
        get() = isMyTurn && moveToSend != null && !sending && !loading

    val canPass: Boolean
        get() = isMyTurn && detail != null && !sending && !loading
}

class BoardViewModel(
    private val gameId: GameId,
    private val session: SessionManager,
    private val games: GamesStore,
    private val onMoveSent: (GameId) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    // Not loaded in init: this ViewModel is cached per game across navigations, so the
    // screen calls load() every time it is opened to pull the current server state (the
    // game may have moved on since it was last viewed).

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val detail = session.call { it.game(gameId) }
                val replay = Replay(detail.record)
                _state.update { current ->
                    val previousTrial = current.trial
                    val trial =
                        if (previousTrial != null && previousTrial.base.board == replay.finalPosition.board) {
                            previousTrial
                        } else {
                            // Start with the colour that is actually to move: the user's own
                            // colour on their turn, the opponent's when exploring their move.
                            TrialVariation.start(replay.finalPosition)
                        }
                    current.copy(
                        loading = false,
                        detail = detail,
                        replay = replay,
                        viewingMove = replay.moveCount,
                        trial = trial,
                    )
                }
            } catch (e: GoServerException) {
                _state.update { it.copy(loading = false, error = messageFor(e)) }
            }
        }
    }

    /** Board taps only ever change local state. */
    fun onTap(point: Point) {
        val s = _state.value
        val trial = s.trial ?: return
        if (s.sending) return
        when (val outcome = trial.place(point)) {
            is TrialOutcome.Placed -> {
                _state.update {
                    it.copy(trial = outcome.variation, viewingMove = it.moveCount, notice = null)
                }
            }

            is TrialOutcome.Rejected -> {
                // Illegal point: the variation is left exactly as it was.
            }
        }
    }

    fun undo() = _state.update { it.copy(trial = it.trial?.undo()) }

    fun reset() = _state.update { it.copy(trial = it.trial?.reset()) }

    fun showPrevious() =
        _state.update {
            if (it.isTrial) it else it.copy(viewingMove = (it.viewingMove - 1).coerceAtLeast(0))
        }

    fun showNext() =
        _state.update {
            if (it.isTrial) it else it.copy(viewingMove = (it.viewingMove + 1).coerceAtMost(it.moveCount))
        }

    fun showLatest() = _state.update { it.copy(viewingMove = it.moveCount) }

    fun askSend() {
        if (_state.value.canSend) _state.update { it.copy(confirmation = PendingConfirmation.SEND) }
    }

    fun askPass() {
        if (_state.value.canPass) _state.update { it.copy(confirmation = PendingConfirmation.PASS) }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val s = _state.value
        val detail = s.detail ?: return
        val move: Move =
            when (s.confirmation) {
                PendingConfirmation.SEND -> s.moveToSend?.move ?: return
                PendingConfirmation.PASS -> Move.Pass
                null -> return
            }
        _state.update { it.copy(confirmation = null, sending = true, notice = null) }
        viewModelScope.launch {
            try {
                session.call { it.sendMove(gameId, detail.summary.moveToken, move) }
                games.remove(gameId)
                _state.update { it.copy(sending = false, notice = "Sent") }
                delay(600)
                onMoveSent(gameId)
            } catch (e: GoServerException.StaleGame) {
                _state.update { it.copy(sending = false, notice = "The game changed on the server. Reloaded.") }
                load()
            } catch (e: GoServerException) {
                // The trial variation is kept so nothing is lost.
                _state.update { it.copy(sending = false, error = messageFor(e)) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun messageFor(e: GoServerException): String {
        val server = session.serverName
        return when (e) {
            is GoServerException.Unreachable -> "Couldn't reach $server. Check your connection and try again."
            is GoServerException.MoveRejected -> "$server rejected the move (${e.code})."
            is GoServerException.RateLimited -> "$server asked us to slow down. Try again in a few minutes."
            is GoServerException.SessionExpired -> "Signed out by $server. Sign in again."
            is GoServerException.InvalidCredentials -> "Signed out by $server. Sign in again."
            else -> "Something went wrong: ${e.message}"
        }
    }
}
