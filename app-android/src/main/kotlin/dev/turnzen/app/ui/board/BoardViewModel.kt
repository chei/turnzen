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
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PendingConfirmation { SEND }

/** DGS's code for a move sent when it is the opponent's turn. */
private const val CODE_NOT_YOUR_TURN = "not_your_turn"

/**
 * The board state is navigated by a single [cursor] over one timeline:
 *
 *     0 .......... serverCount .......... total
 *     start        current server state   end of trial
 *
 * `0..serverCount` replays the played moves; `serverCount..total` walks the trial moves on
 * top of the current position. Tapping the board branches a trial from the cursor (only at
 * or after the current state); sending always transmits the trial's first move.
 */
data class BoardUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: GameDetail? = null,
    val replay: Replay? = null,
    val trial: TrialVariation? = null,
    val cursor: Int = 0,
    val sending: Boolean = false,
    val confirmation: PendingConfirmation? = null,
    /** Short-lived feedback, e.g. "The game changed on the server. Reloaded." */
    val notice: String? = null,
    /** The summary known from the games list, shown while the full record loads. */
    val preview: GameSummary? = null,
) {
    /** The summary to render the header from, before or after the record arrives. */
    val headerSummary: GameSummary?
        get() = detail?.summary ?: preview

    /** Board size, known from the games list even while loading, so the grid can be drawn. */
    val boardSize: Int?
        get() = detail?.record?.boardSize ?: preview?.boardSize

    /** Number of played server moves; also the header's "move N". */
    val moveCount: Int
        get() = replay?.moveCount ?: 0

    val trialCount: Int
        get() = trial?.size ?: 0

    private val total: Int
        get() = moveCount + trialCount

    val hasTrial: Boolean
        get() = trialCount > 0

    /** False when the game is open only to explore the opponent's move; no move can be sent.
     *  Uses the preview while loading so the Send button takes its final place from the start. */
    val isMyTurn: Boolean
        get() = headerSummary?.isMyTurn ?: false

    val atStart: Boolean
        get() = cursor <= 0

    val atCurrent: Boolean
        get() = cursor == moveCount

    val atEnd: Boolean
        get() = cursor >= total

    val canGoBack: Boolean
        get() = cursor > 0

    val canGoForward: Boolean
        get() = cursor < total

    /** How far into the trial the cursor is (0 when at or before the current state). */
    val trialDepthShown: Int
        get() = (cursor - moveCount).coerceIn(0, trialCount)

    /** What the board shows at the cursor. */
    val position: Position?
        get() =
            when {
                cursor <= moveCount -> replay?.positionAfter(cursor)
                else -> trial?.positionAt(trialDepthShown)
            }

    /** Trial stones visible at the cursor (for numbering/rings); empty before the trial. */
    val trialMoves: List<ColoredMove>
        get() = if (cursor > moveCount) trial?.moves?.take(trialDepthShown) ?: emptyList() else emptyList()

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

    /**
     * Send is armed only while viewing exactly one move past the current state, so a
     * multi-move exploration can never be sent by accident, and scrubbing back to the
     * current state disarms it. The move sent is that single trial stone.
     */
    val canSend: Boolean
        get() = isMyTurn && cursor == moveCount + 1 && !sending && !loading

    /** Tapping the board places a stone only at or after the current state. */
    val canPlace: Boolean
        get() = !sending && cursor >= moveCount && trial != null
}

class BoardViewModel(
    private val gameId: GameId,
    private val session: SessionManager,
    private val games: GamesStore,
    /** The game the queue moves to after this one; its record is warmed in the background. */
    private val nextGame: () -> GameId? = { null },
    private val onMoveSent: (GameId) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    // Not loaded in init: this ViewModel is cached per game across navigations, so the
    // screen calls load() every time it is opened to pull the current server state (the
    // game may have moved on since it was last viewed).

    /**
     * Opening or reopening a game. A trial variation is local scratch work, so it is dropped
     * here and the board lands on the current position.
     *
     * The record already held — loaded by this ViewModel earlier, or warmed in the background
     * by [GamesStore.prefetch] — is used when the games list (refreshed whenever the list is
     * shown) agrees the game has not moved on since. That makes opening a warm game
     * instantaneous and costs no request. Otherwise the game *has* moved on (the user played
     * in it, or the opponent has), so the held record would flash a stale position that then
     * jumps: show the loading placeholder instead and fetch the real one.
     *
     * [force] downloads unconditionally, for the one case where the server has told us
     * directly that the game moved on — the list has not caught up yet and must not be
     * trusted.
     */
    fun load(force: Boolean = false) {
        val preview = games.summary(gameId)
        val known = if (force) null else _state.value.detail ?: games.cachedDetail(gameId)
        if (known != null && (preview == null || preview.moveNumber <= known.summary.moveNumber)) {
            val replay = Replay(known.record)
            _state.update {
                it.copy(
                    loading = false,
                    error = null,
                    preview = preview ?: it.preview,
                    detail = known,
                    replay = replay,
                    trial = TrialVariation.start(replay.finalPosition),
                    cursor = replay.moveCount,
                )
            }
            warmNext()
            return
        }
        _state.update {
            it.copy(
                loading = true,
                error = null,
                preview = preview ?: it.preview,
                detail = null,
                replay = null,
                trial = null,
                cursor = 0,
            )
        }
        viewModelScope.launch {
            try {
                val detail = session.call { it.game(gameId) }
                games.cache(detail)
                val replay = Replay(detail.record)
                _state.update { current ->
                    current.copy(
                        loading = false,
                        detail = detail,
                        replay = replay,
                        // A fresh trial on the current position, coloured for the side to move.
                        trial = TrialVariation.start(replay.finalPosition),
                        cursor = replay.moveCount,
                    )
                }
                warmNext()
            } catch (e: GoServerException) {
                _state.update { it.copy(loading = false, error = messageFor(e)) }
            }
        }
    }

    /**
     * Download the next game in the queue while the user studies this one, so the jump after
     * a Send (or Skip) lands on a board that is already there. One game ahead only — see
     * [GamesStore.prefetch].
     */
    private fun warmNext() {
        val next = nextGame() ?: return
        viewModelScope.launch { games.prefetch(next) }
    }

    /** Board taps only ever change local state: they branch a trial from the cursor. */
    fun onTap(point: Point) {
        val s = _state.value
        val trial = s.trial ?: return
        if (!s.canPlace) return
        // Tapping the newest trial stone takes it back, exactly as the step-back button
        // does, so a variation can be walked forward and back without leaving the board.
        if (s.trialMoves.lastOrNull()?.point == point) {
            stepBack()
            return
        }
        val fromDepth = trial.truncatedTo(s.trialDepthShown)
        when (val outcome = fromDepth.place(point)) {
            is TrialOutcome.Placed -> {
                val next = outcome.variation
                _state.update {
                    it.copy(trial = next, cursor = it.moveCount + next.size, notice = null)
                }
            }

            is TrialOutcome.Rejected -> {
                // Illegal point: nothing changes.
            }
        }
    }

    fun toStart() = _state.update { it.copy(cursor = 0) }

    fun stepBack() = _state.update { it.copy(cursor = (it.cursor - 1).coerceAtLeast(0)) }

    fun toCurrent() = _state.update { it.copy(cursor = it.moveCount) }

    fun stepForward() = _state.update { it.copy(cursor = (it.cursor + 1).coerceAtMost(it.moveCount + it.trialCount)) }

    fun toEnd() = _state.update { it.copy(cursor = it.moveCount + it.trialCount) }

    fun askSend() {
        if (_state.value.canSend) _state.update { it.copy(confirmation = PendingConfirmation.SEND) }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val s = _state.value
        val detail = s.detail ?: return
        if (s.confirmation != PendingConfirmation.SEND) return
        val move: Move = s.moveToSend?.move ?: return
        _state.update { it.copy(confirmation = null, sending = true, notice = null) }
        viewModelScope.launch {
            try {
                session.call { it.sendMove(gameId, detail.summary.moveToken, move) }
                _state.update { it.copy(sending = false) }
                // A short beat before auto-advancing to the next game, so the send does
                // not feel like an instant, unexplained jump.
                delay(600)
                // Advance first (nextAfter still sees this game, so the queue steps to the
                // one after it, or back to the list if it was the last), then drop it.
                onMoveSent(gameId)
                games.onMovePlayed(gameId)
            } catch (e: GoServerException.StaleGame) {
                onGameMovedOn()
            } catch (e: GoServerException.MoveRejected) {
                // "Not your turn" is not a bad move, it means the game moved on without us
                // (the opponent played, or this player moved in another client). Treat it
                // like a stale game rather than showing a rejection the user cannot act on.
                if (e.code == CODE_NOT_YOUR_TURN) {
                    onGameMovedOn()
                } else {
                    _state.update { it.copy(sending = false, error = messageFor(e)) }
                }
            } catch (e: GoServerException) {
                // The trial variation is kept so nothing is lost.
                _state.update { it.copy(sending = false, error = messageFor(e)) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * The game moved on without us. Reload it so the board shows the real position, and
     * re-check the games list in the background: the same event that invalidated this move
     * usually changed whose turn it is elsewhere, and the list is what the user goes back to.
     */
    private fun onGameMovedOn() {
        _state.update { it.copy(sending = false, notice = "The game changed on the server. Reloaded.") }
        load(force = true)
        viewModelScope.launch {
            try {
                games.refresh()
            } catch (e: GoServerException) {
                // Best effort: the list keeps what it has and refreshes again when shown.
            }
        }
    }

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
