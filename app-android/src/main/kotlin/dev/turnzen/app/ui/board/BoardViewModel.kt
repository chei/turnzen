package dev.turnzen.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.LadderStandings
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.ui.common.ServerProblem
import dev.turnzen.app.ui.common.problemFor
import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Coordinates
import dev.turnzen.game.GameResult
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.Position
import dev.turnzen.game.Replay
import dev.turnzen.game.ScoreCount
import dev.turnzen.game.Scoring
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.game.TrialOutcome
import dev.turnzen.game.TrialVariation
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.ScoreState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PendingConfirmation { SEND, PASS, RESIGN, SEND_MARKS, ACCEPT_SCORE }

/**
 * The scoring phase: both players mark the stones they consider dead, and agree on what that
 * leaves. [server] is the state DGS last told us — its marks, its territory and its count,
 * which is the count that settles the game. [dead] is what the board shows now, including
 * marks the user has made since; [toggled] carries one point per group they flipped, which is
 * exactly what the server wants to hear about.
 *
 * Marking is local, like every other board gesture: nothing reaches the server until the user
 * sends the marks or accepts the score.
 */
data class ScoringUiState(
    val server: ScoreState,
    val dead: Set<Point>,
    val toggled: Set<Point> = emptySet(),
    /** The count for [dead], worked out on the device while the user is still marking. */
    val count: ScoreCount? = null,
) {
    /** True while the board shows marks the server has not seen. */
    val changed: Boolean
        get() = toggled.isNotEmpty()

    /**
     * The result to show. Until the user touches anything that is the server's own count —
     * the authority, and what an agreement agrees to. Once they mark something it is the
     * local count, because the server's answer is about a different set of stones.
     */
    val result: GameResult?
        get() = if (changed) count?.result else server.result ?: count?.result

    val blackTerritory: Set<Point>
        get() = if (changed) count?.territoryOf(StoneColor.BLACK).orEmpty() else server.blackTerritory

    val whiteTerritory: Set<Point>
        get() = if (changed) count?.territoryOf(StoneColor.WHITE).orEmpty() else server.whiteTerritory

    /**
     * The device's own count, but only when the server's is the one on show and the two have
     * parted company by more than rounding; null the rest of the time.
     *
     * The panel shows the server's figure while the marks are untouched and breaks down the
     * device's count underneath it, because the server sends a result and territory but no
     * per-side totals. Usually both answers agree and nobody notices the seam. When they do
     * not — a seki, dame left unfilled under area scoring — the screen has to say so rather
     * than print two numbers that refuse to add up.
     */
    val localAside: GameResult?
        get() {
            if (changed) return null
            val settled = server.result ?: return null
            val local = count?.result ?: return null
            return local.takeIf { abs(it.signedMargin - settled.signedMargin) > 0.01 }
        }
}

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
    /** Why the last call failed, if it did: a load that found no network, a rejected move. */
    val problem: ServerProblem? = null,
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
    /** Non-null once the game has reached its scoring phase. */
    val scoring: ScoringUiState? = null,
    /**
     * Where the two players stand on the ladder, once it has been fetched. Arrives after the
     * info sheet is already on screen, and stays null for every game that is not a ladder
     * challenge — or whose standing could not be read, which is not worth telling anyone.
     */
    val ladderStanding: LadderStanding? = null,
) {
    /** The game is being counted, so the board marks dead stones instead of trying moves. */
    val isScoring: Boolean
        get() = scoring != null

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

    /**
     * The game is over and open for reading: the board replays it and trial stones still
     * explore it, but nothing can be sent. Every action already hangs off [isMyTurn], which
     * a finished game never has, so this exists for what the screen *shows* — a result
     * instead of a clock, and no action row at all.
     */
    val isFinished: Boolean
        get() = headerSummary?.isFinished ?: false

    /** How the game ended, for a finished game the server stated a result for. */
    val result: GameResult?
        get() = headerSummary?.result

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
        get() = !sending && !loading && replay != null && !isScoring

    /** Marking is the user's own business; only sending the marks needs their turn. */
    val canMark: Boolean
        get() = isScoring && !sending && !loading

    /** Sending marks and agreeing are moves in the scoring phase, so they need the turn. */
    val canSettleScore: Boolean
        get() = isScoring && isMyTurn && !sending && !loading

    /**
     * Passing is a move like any other, so it needs the same thing Send needs: the user's
     * turn and a loaded game. It does not care where the cursor stands — what is on the
     * board is scratch work, and a pass answers the position the server is waiting on.
     */
    val canPass: Boolean
        get() = isMyTurn && detail != null && !sending && !loading

    /** The opponent's last move was a pass, so they have offered to end the game. */
    val opponentPassed: Boolean
        get() =
            replay
                ?.record
                ?.moves
                ?.lastOrNull()
                ?.move == Move.Pass

    /**
     * Whether Pass takes the action row's right slot — the slot that always holds the move
     * that would go to the server. It does so once the opponent has passed and Send has
     * nothing armed to send: a pass is then the move on offer, and that is the moment a
     * player looks for the button. An armed Send outranks it, so trying a stone puts "Send
     * D10" back in the slot and stepping off it brings Pass back. Outside this state passing
     * stays in the game-info sheet, where it costs the board nothing.
     */
    val passPromoted: Boolean
        get() = canPass && opponentPassed && !canSend
}

class BoardViewModel(
    private val gameId: GameId,
    private val session: SessionManager,
    private val games: GamesStore,
    /** Ladder standings for the info sheet; absent in tests that do not care about them. */
    private val ladders: LadderStandings? = null,
    /** The game the queue moves to after this one; its record is warmed in the background. */
    private val nextGame: () -> GameId? = { null },
    /** The game is over (resigned): close the board rather than step to the next game. */
    private val onGameEnded: () -> Unit = {},
    /** Last so that the common case — only this callback — can be a trailing lambda. */
    private val onMoveSent: (GameId) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    /** For copy that names the server ("finish it on Dragon Go Server"). */
    val serverName: String
        get() = session.serverName

    /** This game's page on the server's website, or null if it has none. */
    val gameUrl: String?
        get() = session.gameUrl(gameId)

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
                    problem = null,
                    preview = preview ?: it.preview,
                    detail = known,
                    replay = replay,
                    trial = TrialVariation.start(replay.finalPosition),
                    trialStart = replay.moveCount,
                    cursor = replay.moveCount,
                    scoring = null,
                )
            }
            loadScoreIfCounting()
            warmNext()
            return
        }
        _state.update {
            it.copy(
                loading = true,
                problem = null,
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
                        scoring = null,
                    )
                }
                loadScoreIfCounting()
                warmNext()
            } catch (e: GoServerException) {
                _state.update { it.copy(loading = false, problem = problemFor(e, session.serverName)) }
            }
        }
    }

    /**
     * Fetches the scoring state when the game has reached the counting phase.
     *
     * Read-only, and done whoever's turn it is: seeing which stones the opponent has marked
     * is the whole point of the phase, and the board would otherwise show a finished game
     * with no explanation of why it cannot be played.
     */
    private fun loadScoreIfCounting() {
        val s = _state.value
        val summary = s.detail?.summary ?: return
        if (summary.pendingAction != PendingAction.SCORE) return
        viewModelScope.launch {
            try {
                val state = session.call { it.scoreState(gameId, summary.moveToken) }
                _state.update { current -> current.copy(scoring = scoringFrom(current, state, state.deadStones)) }
            } catch (e: GoServerException) {
                // The board is still worth showing; the problem line explains the rest.
                _state.update { it.copy(problem = problemFor(e, session.serverName)) }
            }
        }
    }

    /** Rebuilds the scoring state around a set of dead stones, counting them locally. */
    private fun scoringFrom(
        state: BoardUiState,
        server: ScoreState,
        dead: Set<Point>,
        toggled: Set<Point> = emptySet(),
    ): ScoringUiState? {
        val position = state.replay?.finalPosition ?: return null
        val komi = state.detail?.info?.komi ?: state.detail?.record?.komi ?: 0.0
        val rules = server.rules ?: state.detail?.info?.scoring ?: ScoringRules.TERRITORY
        // Only area scoring cares; Scoring.count ignores it under territory rules.
        val handicap = state.detail?.summary?.handicap ?: 0
        return ScoringUiState(
            server = server,
            dead = dead,
            toggled = toggled,
            count = Scoring.count(position, dead, komi, rules, handicap),
        )
    }

    /**
     * Marks the group under the finger dead, or brings it back to life. Local, like every
     * board gesture: the marks travel only when the user sends them.
     *
     * The server is told in groups, not stones — one point per group flipped — so a group
     * flipped twice cancels out and is not mentioned at all. The lowest point of the group
     * stands for it, so the same group always cancels itself whichever stone was tapped.
     */
    fun toggleDead(point: Point) {
        val s = _state.value
        val scoring = s.scoring ?: return
        if (!s.canMark) return
        val board = s.replay?.finalPosition?.board ?: return
        val group = Scoring.groupAt(board, point)
        if (group.isEmpty()) return
        val representative = group.minWith(compareBy({ it.row }, { it.col }))
        val dead = if (group.all { it in scoring.dead }) scoring.dead - group else scoring.dead + group
        val toggled =
            if (representative in scoring.toggled) {
                scoring.toggled - representative
            } else {
                scoring.toggled + representative
            }
        _state.update { current ->
            current.copy(scoring = scoringFrom(current, scoring.server, dead, toggled), notice = null)
        }
    }

    /** Drops the user's own marks and goes back to the ones the server holds. */
    fun resetMarks() {
        val s = _state.value
        val scoring = s.scoring ?: return
        _state.update { current ->
            current.copy(scoring = scoringFrom(current, scoring.server, scoring.server.deadStones))
        }
    }

    fun askSendMarks() {
        val s = _state.value
        if (s.canSettleScore && s.scoring?.changed == true) {
            _state.update { it.copy(confirmation = PendingConfirmation.SEND_MARKS) }
        }
    }

    fun askAcceptScore() {
        val s = _state.value
        if (s.canSettleScore && s.scoring?.changed == false) {
            _state.update { it.copy(confirmation = PendingConfirmation.ACCEPT_SCORE) }
        }
    }

    /**
     * Sends the marks the user made as a disagreement with the current ones, or agrees with
     * the score as it stands. Both are moves in the scoring phase — the turn passes — so both
     * step the queue on exactly as sending a stone does.
     */
    private fun settleScore(accept: Boolean) {
        val s = _state.value
        val detail = s.detail ?: return
        val toggled = s.scoring?.toggled.orEmpty()
        _state.update { it.copy(confirmation = null, sending = true, notice = null) }
        viewModelScope.launch {
            try {
                session.call {
                    if (accept) {
                        it.acceptScore(gameId, detail.summary.moveToken)
                    } else {
                        it.disputeScore(gameId, detail.summary.moveToken, toggled)
                    }
                }
                _state.update { it.copy(sending = false) }
                delay(600)
                onMoveSent(gameId)
                games.onMovePlayed(gameId)
            } catch (e: GoServerException.StaleGame) {
                onGameMovedOn()
            } catch (e: GoServerException) {
                _state.update { it.copy(sending = false, problem = problemFor(e, session.serverName)) }
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
        // The mirror of that gesture: tapping the point the game played *next* replays it,
        // exactly as the forward button does. Rewinding and tapping the move back in is
        // otherwise indistinguishable from proposing it as a what-if, which used to leave the
        // board holding a one-stone variation that merely coincided with the game — the same
        // stones as the server, but marked as a trial and unable to claim the live position.
        // Only this exact point qualifies, so a genuine what-if is never swallowed; and only
        // while the cursor is on the played line, since once a variation branches here
        // "forward" means into that variation, not into the game.
        if (s.cursor < s.trialStart) {
            val next =
                replay.record.moves
                    .getOrNull(s.cursor)
                    ?.move
            if (next is Move.Play && next.point == point) {
                stepForward()
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

    /**
     * Moves the cursor [steps] along the timeline, clamped to its ends. This is what a drag
     * across the board does: the gesture reports the whole moves it has crossed since it last
     * reported, so one drag arrives as a stream of small deltas rather than as a position.
     * Relative on purpose — an absolute mapping from finger position to move number would put
     * a 250-move game at under a point per move, and stepping back two would be unhittable.
     */
    fun scrubBy(steps: Int) = _state.update { it.copy(cursor = (it.cursor + steps).coerceIn(0, it.total)) }

    fun toEnd() = _state.update { it.copy(cursor = it.total) }

    fun askSend() {
        if (_state.value.canSend) _state.update { it.copy(confirmation = PendingConfirmation.SEND) }
    }

    /** Passing and resigning both go through a confirmation; neither can be undone locally. */
    fun askPass() {
        if (_state.value.canPass) _state.update { it.copy(confirmation = PendingConfirmation.PASS) }
    }

    fun askResign() {
        if (_state.value.detail != null && !_state.value.sending) {
            _state.update { it.copy(confirmation = PendingConfirmation.RESIGN) }
        }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val s = _state.value
        when (s.confirmation) {
            PendingConfirmation.SEND -> s.moveToSend?.move?.let { send(it) }
            PendingConfirmation.PASS -> send(Move.Pass)
            PendingConfirmation.RESIGN -> resign()
            PendingConfirmation.SEND_MARKS -> settleScore(accept = false)
            PendingConfirmation.ACCEPT_SCORE -> settleScore(accept = true)
            null -> Unit
        }
    }

    /**
     * The info sheet has been opened. Everything else in it is already known, so the sheet is
     * on screen before this runs and the standing appends underneath when it arrives — the
     * one row in there that costs a request.
     *
     * Fetched here rather than in [load] because most games are not ladder challenges and
     * most openings never reach the sheet: paying for a standings page on the way to playing
     * a move would be exactly the wrong trade.
     */
    fun onInfoOpened() {
        if (ladders == null || _state.value.ladderStanding != null) return
        val tournament =
            _state.value.detail
                ?.info
                ?.tournament ?: return
        if (!tournament.isLadder) return
        viewModelScope.launch {
            // Never throws, and a null simply leaves the row off: this is decoration, and the
            // sheet's own content is what reports a session or network problem.
            val standing = ladders.standing(tournament, gameId)
            if (standing != null) _state.update { it.copy(ladderStanding = standing) }
        }
    }

    /**
     * Gives the game up. Unlike a move, this does not step the queue on: resigning is a
     * deliberate exit, so the board closes and the user lands back on their games.
     */
    private fun resign() {
        val detail = _state.value.detail ?: return
        _state.update { it.copy(confirmation = null, sending = true, notice = null) }
        viewModelScope.launch {
            try {
                session.call { it.resign(gameId, detail.summary.moveToken) }
                _state.update { it.copy(sending = false) }
                // Ranks move exactly when a ladder game ends, ours among them.
                ladders?.invalidate(detail.info?.tournament)
                games.onGameEnded(gameId)
                onGameEnded()
            } catch (e: GoServerException.StaleGame) {
                onGameMovedOn()
            } catch (e: GoServerException) {
                _state.update { it.copy(sending = false, problem = problemFor(e, session.serverName)) }
            }
        }
    }

    private fun send(move: Move) {
        val detail = _state.value.detail ?: return
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
                    _state.update { it.copy(sending = false, problem = problemFor(e, session.serverName)) }
                }
            } catch (e: GoServerException) {
                // The trial variation is kept so nothing is lost.
                _state.update { it.copy(sending = false, problem = problemFor(e, session.serverName)) }
            }
        }
    }

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
}
