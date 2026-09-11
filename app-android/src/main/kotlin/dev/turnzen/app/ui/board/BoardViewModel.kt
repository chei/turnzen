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
 *     0 .......... trialStart .......... total
 *     start        where the trial       end of trial
 *                  branches off
 *
 * `0..trialStart` replays the played moves; `trialStart..total` walks the trial moves on top
 * of the position there. [trialStart] is the current server state in the ordinary case, so
 * the timeline is the whole game followed by the variation being considered.
 *
 * Tapping an empty point branches a trial from wherever the cursor is — including a rewound
 * position, which is how "what if I had played there instead" works. That line then replaces
 * the game's later moves on the timeline, and [trialStart] sits in the past until the user
 * returns to the current state. Sending is possible only from a line that branches at the
 * current state ([trialFromCurrent]); a what-if about a past position answers a question the
 * game has already moved beyond.
 */
data class BoardUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: GameDetail? = null,
    val replay: Replay? = null,
    val trial: TrialVariation? = null,
    /** The played-move index the trial branches from; equals [moveCount] unless rewound. */
    val trialStart: Int = 0,
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

    /** The far end of the timeline: the trial's branch point plus its moves. */
    val total: Int
        get() = trialStart + trialCount

    val hasTrial: Boolean
        get() = trialCount > 0

    /**
     * True while the trial branches from the current server state — the only line whose
     * first stone is a legal answer to the position the opponent is actually waiting on.
     */
    val trialFromCurrent: Boolean
        get() = trialStart == moveCount

    /** False when the game is open only to explore the opponent's move; no move can be sent.
     *  Uses the preview while loading so the Send button takes its final place from the start. */
    val isMyTurn: Boolean
        get() = headerSummary?.isMyTurn ?: false

    val atStart: Boolean
        get() = cursor <= 0

    /** Viewing the real current position, not a what-if branched out of the past. */
    val atCurrent: Boolean
        get() = trialFromCurrent && cursor == moveCount

    val atEnd: Boolean
        get() = cursor >= total

    val canGoBack: Boolean
        get() = cursor > 0

    val canGoForward: Boolean
        get() = cursor < total

    /** How far into the trial the cursor is (0 when at or before the branch point). */
    val trialDepthShown: Int
        get() = (cursor - trialStart).coerceIn(0, trialCount)

    /** What the board shows at the cursor. */
    val position: Position?
        get() =
            when {
                cursor <= trialStart -> replay?.positionAfter(cursor)
                else -> trial?.positionAt(trialDepthShown)
            }

    /** Trial stones visible at the cursor (for numbering/rings); empty before the trial. */
    val trialMoves: List<ColoredMove>
        get() = if (cursor > trialStart) trial?.moves?.take(trialDepthShown) ?: emptyList() else emptyList()

    /** The move Send would transmit: the first stone of a line branching at the current state. */
    val moveToSend: ColoredMove?
        get() = if (trialFromCurrent) trial?.firstMove else null

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
        get() = isMyTurn && trialFromCurrent && cursor == moveCount + 1 && !sending && !loading

    /** The board is live once a record is loaded: taps explore from wherever the cursor is. */
    val canPlace: Boolean
        get() = !sending && !loading && replay != null
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
                    trialStart = replay.moveCount,
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
                trialStart = 0,
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
                        trialStart = replay.moveCount,
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

    /** Board taps only ever change local state: they rewind, or branch a trial from the cursor. */
    fun onTap(point: Point) {
        val s = _state.value
        if (!s.canPlace) return
        val replay = s.replay ?: return
        // Tapping the newest stone on the board takes it back, exactly as the step-back
        // button does, so a game can be walked backwards with the same gesture that walks a
        // variation backwards. That is the newest trial stone when one is shown...
        if (s.trialMoves.lastOrNull()?.point == point) {
            stepBack()
            return
        }
        // ...and otherwise the most recently played stone, which rewinds past it. Played
        // stones are of course not removed from the game, only stepped behind.
        if (s.cursor in 1..s.trialStart) {
            val played =
                replay.record.moves
                    .getOrNull(s.cursor - 1)
                    ?.move
            if (played is Move.Play && played.point == point) {
                stepBack()
                return
            }
        }
        // Any other point starts or extends a variation from wherever the cursor is. Rewound
        // into the game, that branches a new line off the past — "what if I had played here
        // instead" — replacing the moves that followed for as long as the line is being read.
        val branchAt = minOf(s.cursor, s.trialStart)
        val from =
            if (s.cursor <= s.trialStart) {
                TrialVariation.start(replay.positionAfter(s.cursor))
            } else {
                s.trial?.truncatedTo(s.trialDepthShown) ?: return
            }
        when (val outcome = from.place(point)) {
            is TrialOutcome.Placed -> {
                val next = outcome.variation
                _state.update {
                    it.copy(
                        trial = next,
                        trialStart = branchAt,
                        cursor = branchAt + next.size,
                        notice = null,
                    )
                }
            }

            is TrialOutcome.Rejected -> {
                // Illegal point: nothing changes.
            }
        }
    }

    fun toStart() = _state.update { it.copy(cursor = 0) }

    fun stepBack() = _state.update { it.copy(cursor = (it.cursor - 1).coerceAtLeast(0)) }

    /**
     * Back to the live position. A line branched out of the past is dropped on the way: it
     * answers a question about a position the game has already moved beyond, so keeping it
     * would leave the board showing moves that never happened.
     */
    fun toCurrent() =
        _state.update { s ->
            if (s.trialFromCurrent) {
                s.copy(cursor = s.moveCount)
            } else {
                s.copy(
                    trial = s.replay?.let { TrialVariation.start(it.finalPosition) },
                    trialStart = s.moveCount,
                    cursor = s.moveCount,
                )
            }
        }

    fun stepForward() = _state.update { it.copy(cursor = (it.cursor + 1).coerceAtMost(it.total)) }

    fun toEnd() = _state.update { it.copy(cursor = it.total) }

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
