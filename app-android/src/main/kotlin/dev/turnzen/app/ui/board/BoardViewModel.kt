package dev.turnzen.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.LadderStandings
import dev.turnzen.app.data.MoveOutbox
import dev.turnzen.app.data.OutgoingMove
import dev.turnzen.app.data.ReturnedMove
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
import dev.turnzen.game.ResultReason
import dev.turnzen.game.ScoreCount
import dev.turnzen.game.Scoring
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.game.TrialOutcome
import dev.turnzen.game.TrialVariation
import dev.turnzen.goserver.GameComment
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

enum class PendingConfirmation { PASS, RESIGN, SEND_MARKS, ACCEPT_SCORE }

/** Something that happened in the game and left no stone on the board to show for it. */
enum class BoardEventKind { PASS, RESIGNATION, TIMEOUT, FORFEIT }

/**
 * What happened at the position the board is showing, when it is something the board cannot
 * draw: a pass, or the act that ended the game. [by] is the player it was done by — the one
 * who passed, or the one who lost the game that way.
 *
 * Without this the board goes quiet at exactly the moments that need explaining. Stepping
 * back through a game that ended in two passes moves the counter and changes nothing on the
 * board, three times over; and in a running game, "your opponent passed" is the whole reason
 * the Pass button has appeared.
 */
data class BoardEvent(
    val kind: BoardEventKind,
    val by: StoneColor,
)

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
     * The game is being counted on a server whose scoring Turnzen does not do (OGS, for now):
     * nothing can be sent from here, and the problem line points to the web.
     */
    val scoringOnTheWeb: Boolean = false,
    /**
     * Where the two players stand on the ladder, once it has been fetched. Arrives after the
     * info sheet is already on screen, and stays null for every game that is not a ladder
     * challenge — or whose standing could not be read, which is not worth telling anyone.
     */
    val ladderStanding: LadderStanding? = null,
    /**
     * The players' comments, oldest first. Null until known: a server that brings them with
     * the game has them here as soon as the record is, one that needs asking (OGS) a moment
     * later — and a game whose comments could not be read simply shows none.
     */
    val comments: List<GameComment>? = null,
    /** What the user has typed to go with their next move. Kept when a send fails. */
    val commentDraft: String = "",
    /** The comment field is open under the action row. */
    val writingComment: Boolean = false,
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
        get() = !scoringOnTheWeb && headerSummary?.isMyTurn ?: false

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

    /**
     * A comment goes with a move, so it can be written exactly when a move can be made. Known
     * from the preview already, so the row is there from the start and never pushes the action
     * row down under a finger once the record arrives.
     */
    val canComment: Boolean
        get() = isMyTurn && !isFinished && !isScoring

    /** The field shows once opened, and stays while it holds something. */
    val showCommentField: Boolean
        get() = canComment && (writingComment || commentDraft.isNotEmpty())

    /**
     * The pass or the ending act at the cursor, if the position on show came about by one.
     *
     * Only ever about the game's own moves: inside a trial line there is nothing of the
     * game's to report, and the scoring phase has its own panel saying where things stand.
     */
    val eventAtCursor: BoardEvent?
        get() {
            if (isScoring || cursor > trialStart) return null
            val played = replay?.record?.moves?.getOrNull(cursor - 1)
            if (played != null && played.move == Move.Pass) {
                return BoardEvent(BoardEventKind.PASS, played.color)
            }
            // The end of a finished game is worth a line of its own when no move ended it.
            if (!isFinished || cursor != moveCount) return null
            val result = headerSummary?.result ?: return null
            val loser = result.winner?.opposite ?: return null
            return when (result.by) {
                ResultReason.RESIGNATION -> BoardEvent(BoardEventKind.RESIGNATION, loser)
                ResultReason.TIMEOUT -> BoardEvent(BoardEventKind.TIMEOUT, loser)
                ResultReason.FORFEIT -> BoardEvent(BoardEventKind.FORFEIT, loser)
                else -> null
            }
        }

    /**
     * The comments written with the game move on show, which is the most recent move the
     * board's line shares with the game: inside a trial that is the move it branched from, so
     * the opponent's message stays in view while the answer to it is being worked out. At the
     * current position of a running game this is what the opponent said with their last move.
     */
    val commentsAtCursor: List<GameComment>
        get() {
            val at = minOf(cursor, trialStart)
            return comments?.filter { it.afterMove == at }.orEmpty()
        }

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
    /** Where a sent move waits out its undo window; see [MoveOutbox]. */
    private val outbox: MoveOutbox,
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

    /**
     * The move number of the record whose clocks were downloaded this time the game was
     * opened; null when the board shows a held record instead.
     */
    private var clocksFetchedAt: Int? = null

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
        // A move in this game is still on its way: whether it lands decides what the board
        // should show, so wait for it (sending it now if it was waiting out its undo window).
        if (outbox.isOutgoing(gameId)) {
            _state.update { it.copy(loading = true, preview = preview ?: it.preview) }
            viewModelScope.launch {
                outbox.awaitDelivery(gameId)
                load(force = true)
            }
            return
        }
        // A move that came back — undone, or not delivered — goes back on the board.
        val back = outbox.claim(gameId)
        val refetch = force || back?.movedOn == true
        val held = if (refetch) null else _state.value.detail ?: games.cachedDetail(gameId)
        // The record can be days old on a slow game; the list's clock is the current one.
        val known = held?.let(games::withListClock)
        if (known != null && (preview == null || preview.moveNumber <= known.summary.moveNumber)) {
            // A record from the store's cache was fetched on some earlier visit.
            if (held !== _state.value.detail) clocksFetchedAt = null
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
                    cursor = openingCursor(replay, known.summary),
                    scoring = null,
                    comments = known.comments ?: it.comments,
                    writingComment = false,
                )
            }
            restore(back)
            loadScoreIfCounting()
            loadCommentsIfSeparate(known)
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
                clocksFetchedAt = detail.summary.moveNumber
                val replay = Replay(detail.record)
                _state.update { current ->
                    current.copy(
                        loading = false,
                        detail = detail,
                        replay = replay,
                        // A fresh trial on the current position, coloured for the side to move.
                        trial = TrialVariation.start(replay.finalPosition),
                        trialStart = replay.moveCount,
                        cursor = openingCursor(replay, detail.summary),
                        scoring = null,
                        comments = detail.comments ?: current.comments,
                        writingComment = false,
                    )
                }
                restore(back)
                loadScoreIfCounting()
                loadCommentsIfSeparate(detail)
                warmNext()
            } catch (e: GoServerException) {
                _state.update { it.copy(loading = false, problem = problemFor(e, session.serverName)) }
            }
        }
    }

    /**
     * Puts a move that came back where it was: the line it was sent from with its first stone
     * armed, and the comment that went with it, so Send is the retry. Only while the game is
     * still waiting on that very move — once it has moved on, the line answers a position that
     * no longer exists, and the board just says what happened.
     */
    private fun restore(back: ReturnedMove?) {
        back ?: return
        _state.update { s ->
            val detail = s.detail ?: return@update s
            if (back.movedOn || detail.summary.moveToken != back.move.token) {
                return@update s.copy(notice = MOVED_ON)
            }
            val trial = back.move.trial
            s.copy(
                trial = trial ?: s.trial,
                trialStart = s.moveCount,
                cursor = if (trial != null) s.moveCount + 1 else s.cursor,
                commentDraft = back.move.comment.orEmpty(),
                problem = back.error?.let { problemFor(it, session.serverName) },
            )
        }
    }

    /**
     * Asks for the comments when the server keeps them apart from the game (OGS chat), each
     * time the game is opened: a message can arrive without a move, so the record being
     * current says nothing about them. Whatever was shown before stays until the answer is in.
     *
     * Decoration, like the ladder standing: a failure leaves the board as it is and says
     * nothing, since the board and its problem line are about playing, not reading.
     */
    private fun loadCommentsIfSeparate(detail: GameDetail) {
        if (detail.comments != null) return
        viewModelScope.launch {
            try {
                val comments = session.call { it.comments(gameId) }
                _state.update { it.copy(comments = comments) }
            } catch (e: GoServerException) {
                // Nothing to show is the honest answer; the game itself loaded fine.
            }
        }
    }

    /**
     * Where the board lands when a game is opened: the last position anyone played a stone
     * into.
     *
     * For a running game that is simply the current position. A game that was counted ends
     * with the two passes that took it into scoring, and landing on those wastes the first
     * two taps of Back on moves that change nothing visible — the board goes quiet and the
     * player is left wondering whether the button works. So the passes are stepped over on
     * the way in; Forward still walks into them, now with a line under the board saying what
     * they are.
     */
    private fun openingCursor(
        replay: Replay,
        summary: GameSummary,
    ): Int {
        if (!summary.isFinished) return replay.moveCount
        var at = replay.moveCount
        while (at > 0 && replay.record.moves[at - 1].move == Move.Pass) at--
        return at
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
        if (!session.scoresInApp) {
            _state.update {
                it.copy(
                    scoringOnTheWeb = true,
                    problem =
                        ServerProblem(
                            title = "Counting on the web",
                            message =
                                "This game is being counted. Turnzen can't count ${session.serverName} games " +
                                    "yet; open it on the web to agree on the dead stones.",
                            transient = true,
                        ),
                )
            }
            return
        }
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

    /**
     * Sends the armed stone. No confirmation: the button already names the move, and the
     * outbox holds it for a few seconds with an Undo while the board steps on to the next game.
     */
    fun send() {
        val s = _state.value
        if (!s.canSend) return
        s.moveToSend?.move?.let { submit(it) }
    }

    /**
     * Passing and resigning both go through a confirmation. A pass still gets the undo window
     * after it, like any move; the dialog is there for what passing means, which a stone
     * does not need explaining.
     */
    fun askPass() {
        if (_state.value.canPass) _state.update { it.copy(confirmation = PendingConfirmation.PASS) }
    }

    fun askResign() {
        if (_state.value.detail != null && !_state.value.sending) {
            _state.update { it.copy(confirmation = PendingConfirmation.RESIGN) }
        }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    /** Opens the comment field under the action row. */
    fun startComment() = _state.update { it.copy(writingComment = true) }

    /** The field lost focus: out of editing. It stays on screen only while it holds text. */
    fun stopComment() = _state.update { it.copy(writingComment = false) }

    /**
     * The user has left this game (back to the list, Skip, another game). A comment is
     * written for the move about to be made here and now, so an unsent one is dropped rather
     * than waiting, hidden, to ride along with some later move. A move that comes back from
     * the outbox brings its own comment with it.
     */
    fun onLeave() = _state.update { it.copy(writingComment = false, commentDraft = "") }

    /** The comment field under the action row; what is typed goes with the next move. */
    fun onCommentChange(text: String) = _state.update { it.copy(commentDraft = text) }

    fun confirm() {
        val s = _state.value
        when (s.confirmation) {
            PendingConfirmation.PASS -> submit(Move.Pass)
            PendingConfirmation.RESIGN -> resign()
            PendingConfirmation.SEND_MARKS -> settleScore(accept = false)
            PendingConfirmation.ACCEPT_SCORE -> settleScore(accept = true)
            null -> Unit
        }
    }

    /**
     * The info sheet has been opened. Everything else in it is already known, so the sheet is
     * on screen before this runs and the standing appends underneath when it arrives. The
     * other request it may cost is the opponent's running clock (see [refreshOpponentClock]).
     *
     * Fetched here rather than in [load] because most games are not ladder challenges and
     * most openings never reach the sheet: paying for a standings page on the way to playing
     * a move would be exactly the wrong trade.
     */
    fun onInfoOpened() {
        refreshOpponentClock()
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
     * On the opponent's turn their clock is the one running, and a held record has it as it
     * was when the record was fetched, which can be days ago. The games list carries only the
     * user's own clock (see [GamesStore.withListClock]), so the sheet that shows both asks
     * for the game again — once, and only for a held record of a running game.
     */
    private fun refreshOpponentClock() {
        val shown = _state.value.detail ?: return
        if (shown.summary.isFinished || shown.summary.isMyTurn) return
        if (clocksFetchedAt == shown.summary.moveNumber) return
        clocksFetchedAt = shown.summary.moveNumber
        viewModelScope.launch {
            val fresh =
                try {
                    session.call { it.game(gameId) }
                } catch (e: GoServerException) {
                    // The sheet keeps the clocks it has; opening it again tries again.
                    clocksFetchedAt = null
                    return@launch
                }
            games.cache(fresh)
            _state.update { current ->
                val detail = current.detail
                // Only the clocks: a game that moved on meanwhile is the next load's to show.
                if (detail == null || detail.summary.moveNumber != fresh.summary.moveNumber) {
                    current
                } else {
                    current.copy(detail = detail.copy(summary = fresh.summary, info = fresh.info ?: detail.info))
                }
            }
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

    /**
     * Hands [move] to the outbox and steps the queue on at once. The board is done with the
     * move from here: the outbox sends it once the undo window has passed, and a move that
     * comes back is put back on this board the next time it opens (see [restore]).
     */
    private fun submit(move: Move) {
        val s = _state.value
        val detail = s.detail ?: return
        val size = detail.record.boardSize
        outbox.submit(
            OutgoingMove(
                gameId = gameId,
                token = detail.summary.moveToken,
                move = move,
                comment = s.commentDraft.trim().ifEmpty { null },
                label = (move as? Move.Play)?.let { Coordinates.label(it.point, size) },
                opponent = detail.summary.opponent.handle,
                trial = if (move is Move.Play) s.trial else null,
            ),
        )
        _state.update {
            it.copy(confirmation = null, notice = null, problem = null, commentDraft = "", writingComment = false)
        }
        onMoveSent(gameId)
    }

    /**
     * The game moved on without us. Reload it so the board shows the real position, and
     * re-check the games list in the background: the same event that invalidated this move
     * usually changed whose turn it is elsewhere, and the list is what the user goes back to.
     */
    private fun onGameMovedOn() {
        _state.update { it.copy(sending = false, notice = MOVED_ON) }
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

private const val MOVED_ON = "The game changed on the server. Reloaded."
