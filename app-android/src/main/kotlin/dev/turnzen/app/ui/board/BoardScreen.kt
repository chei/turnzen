package dev.turnzen.app.ui.board

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.data.BoardSettings
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.OutcomeLabel
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.ProblemMessage
import dev.turnzen.app.ui.common.RemainingTimeLabel
import dev.turnzen.app.ui.common.ServerDot
import dev.turnzen.app.ui.common.TextAction
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.GameResult
import dev.turnzen.game.Position
import dev.turnzen.game.ResultReason
import dev.turnzen.game.ScoreCount
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.CommentVisibility
import dev.turnzen.goserver.GameComment
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BoardScreen(
    viewModel: BoardViewModel,
    boardSettings: BoardSettings,
    onSkip: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.headerSummary
    val uriHandler = LocalUriHandler.current
    // Saved, not merely remembered: a rotation with the sheet open should not close it.
    var showingInfo by rememberSaveable { mutableStateOf(false) }
    // Draw the real position once loaded; while it loads, an empty grid at the known board
    // size, so the board keeps its final position instead of flashing a blank rectangle.
    val position = state.position ?: state.boardSize?.let { Position.empty(it) }

    // Leaving the game ends any comment in progress. Not on a rotation, which disposes this
    // screen too but leaves the user exactly where they were.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.onLeave() }
    }

    // A tap anywhere but the comment field takes the focus off it, as a tap outside a field
    // does in most apps. Compose does not do this by itself: the board and the buttons take
    // their taps without touching focus, and the field would stay in editing under them.
    val focusManager = LocalFocusManager.current
    var screenCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var commentBounds by remember { mutableStateOf<Rect?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .onGloballyPositioned { screenCoordinates = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Seen first and left unconsumed, so the tap still does what it does.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val at = screenCoordinates?.localToWindow(down.position)
                    if (at == null || commentBounds?.contains(at) != true) focusManager.clearFocus()
                }
            },
    ) {
        val header: @Composable () -> Unit = {
            // No back control of its own: Android's back (gesture or button) closes the game, as
            // does the Games tab. A third affordance only cost the board the
            // vertical space it sat in.
            if (summary != null) {
                val moveNumber = if (state.detail != null) state.moveCount else summary.moveNumber
                Header(
                    server = summary.id.server,
                    opponent = Formatting.opponent(summary.opponent.handle, summary.opponent.rank),
                    subtitle =
                        if (state.isFinished) {
                            stringResource(
                                if (summary.myColor == StoneColor.BLACK) {
                                    R.string.board_played_black
                                } else {
                                    R.string.board_played_white
                                },
                                moveNumber,
                            )
                        } else {
                            "You play ${if (summary.myColor == StoneColor.BLACK) "black" else "white"}, " +
                                "move $moveNumber"
                        },
                    remaining = summary.remainingTime,
                    // A finished game has no clock and nobody to move: the same corner carries
                    // how it ended instead.
                    result = if (state.isFinished) summary.result else null,
                    ratingChange = summary.ratingChange,
                    isFinished = state.isFinished,
                    you = summary.myColor,
                    // The header names the game, so it is also the way into everything else about
                    // it. Only once the record is here: the sheet's actions need its move token.
                    onOpenInfo =
                        if (state.detail != null) {
                            {
                                showingInfo = true
                                // Starts the one thing in the sheet that needs downloading; the
                                // sheet itself opens now, from what is already known.
                                viewModel.onInfoOpened()
                            }
                        } else {
                            null
                        },
                )
            } else {
                Spacer(Modifier.height(60.dp))
            }
        }

        val board: @Composable (Modifier) -> Unit = { boardModifier ->
            if (position != null) {
                GoBoard(
                    position = position,
                    modifier = boardModifier,
                    trialMoves = state.trialMoves,
                    settings = boardSettings,
                    // No interaction with the placeholder grid until the real position loads.
                    enabled = !state.sending && !state.loading,
                    scoring =
                        state.scoring?.takeIf { state.showingScore }?.let {
                            ScoringOverlay(
                                dead = it.dead,
                                blackTerritory = it.blackTerritory,
                                whiteTerritory = it.whiteTerritory,
                            )
                        },
                    // While the count is on show a tap marks a group dead instead of trying a
                    // move: there are no moves left to try. Stepped back into the game, the
                    // board is for reading again.
                    onTap = if (state.showingScore) viewModel::toggleDead else viewModel::onTap,
                    // Scrubbing works even over the count: it is the way back into the game.
                    onScrub = viewModel::scrubBy,
                )
            } else {
                Box(boardModifier.fillMaxWidth().aspectRatio(1f).background(colors.board))
            }
        }

        val controls: @Composable ColumnScope.() -> Unit = {
            // A fixed slot under the board for the move that leaves no stone — a pass, or the
            // act that ended the game. Its height is reserved whether or not there is anything to
            // say, so that scrubbing through a game never shifts the navigation row under the
            // reader's thumb.
            Box(
                Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                val event = state.eventAtCursor
                val who = state.headerSummary
                if (event != null && who != null) {
                    Text(
                        boardEventLabel(event, isYou = event.by == who.myColor, opponent = who.opponent.handle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink2,
                    )
                }
            }

            val scoring = state.scoring
            val summary = state.headerSummary
            if (scoring != null && summary != null && state.showingScore) {
                // The count replaces the move navigation while the board is on the final
                // position, which needs the room. Scrubbing back brings the row back, and its
                // current-move button returns here with the marks as they were left.
                // The summary comes with the record the count is made from, so it is always here;
                // without it there would be no way to say which side is the reader's.
                ScorePanel(
                    scoring = scoring,
                    you = summary.myColor,
                    opponent = summary.opponent.handle,
                    canMark = state.canMark,
                    waitingForOpponent = !state.isMyTurn,
                    onReset = viewModel::resetMarks,
                )
            } else {
                MoveNavigationRow(state, viewModel)
            }

            state.problem?.let {
                ProblemMessage(
                    it,
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    // Retry offered only for a load that never produced a board: a failed send is
                    // retried with Send itself, which still sits below with the variation intact.
                    onRetry = if (state.replay == null) ({ viewModel.load(force = true) }) else null,
                )
            }

            // The only feedback worth a line: a send that was rejected because the game moved
            // on and was reloaded. Appears just when it happens; costs no space otherwise.
            state.notice?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accent,
                )
            }

            // The comment that goes with the next move, written in place rather than in a dialog so
            // that Send stays one tap. Above the action row, so the move navigation and Send are
            // never neighbours: a slip off the forward buttons lands on the comment, not on a move.
            if (state.canComment) {
                if (state.showCommentField) {
                    Spacer(Modifier.height(8.dp))
                    CommentField(
                        value = state.commentDraft,
                        onChange = viewModel::onCommentChange,
                        // Focused when the user has just asked for it, not when a returned move
                        // brings its comment back.
                        focus = state.writingComment,
                        onBlur = viewModel::stopComment,
                        modifier =
                            Modifier
                                .padding(horizontal = 20.dp)
                                .onGloballyPositioned { commentBounds = it.boundsInWindow() },
                    )
                } else {
                    TextAction(
                        stringResource(R.string.board_add_comment),
                        viewModel::startComment,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = colors.accent,
                    )
                }
            }

            // What the players wrote with the move on show. Below everything above it that is
            // operated, and the free space below it takes up the difference, so a comment
            // appearing or going away while scrubbing never moves a button.
            val commenter = state.headerSummary
            val comments = state.commentsAtCursor
            if (commenter != null && comments.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Comments(
                    comments = comments,
                    you = commenter.myColor,
                    opponent = commenter.opponent.handle,
                    finished = state.isFinished,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            // Skip keeps a fixed half-width slot on the left in every game; Send fills the
            // right slot only on the user's own turn (an empty spacer holds it otherwise), so
            // Skip never changes size or position.
            //
            // The row sits at the bottom of the screen, as far from the board as it can be: Send
            // goes in one tap with no undo after it, so a slip off the board must not land on it.
            // Only when the screen is too short for everything does it scroll with the rest.
            //
            // A finished game gets no action row at all: there is no move to send, and Skip is
            // the queue's button — the archive is not a queue, it is a list you came back from.
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.weight(1f))
            if (!state.isFinished) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PillButton(
                        text = stringResource(R.string.board_skip),
                        onClick = onSkip,
                        enabled = !state.sending,
                        modifier = Modifier.weight(1f),
                    )
                    if (scoring != null) {
                        // In the counting phase the right slot settles the score: the marks, when the
                        // user has made any, and otherwise agreement with the count as it stands.
                        if (state.isMyTurn) {
                            PillButton(
                                text =
                                    when {
                                        state.sending -> stringResource(R.string.board_sending)
                                        scoring.changed -> stringResource(R.string.score_send_marks)
                                        else -> stringResource(R.string.score_accept)
                                    },
                                onClick = if (scoring.changed) viewModel::askSendMarks else viewModel::askAcceptScore,
                                primary = true,
                                enabled = state.canSettleScore,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    } else if (state.isMyTurn) {
                        // The right slot always holds the move you would send. Normally that is Send
                        // — armed with a trial stone, disabled without one. When the opponent has
                        // passed and no stone is armed, the move on offer is a pass, so Pass takes
                        // the slot: outlined, not filled, because giving up the move is not the
                        // recommended answer, only an available one.
                        if (state.passPromoted) {
                            PillButton(
                                text = stringResource(R.string.board_pass),
                                onClick = viewModel::askPass,
                                enabled = !state.sending,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            PillButton(
                                text =
                                    when {
                                        state.sending -> {
                                            stringResource(R.string.board_sending)
                                        }

                                        state.canSend && state.sendLabel != null -> {
                                            "${stringResource(R.string.board_send)} ${state.sendLabel}"
                                        }

                                        else -> {
                                            stringResource(R.string.board_send)
                                        }
                                    },
                                onClick = viewModel::send,
                                primary = true,
                                enabled = state.canSend,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // A window with room beside a full-height board — a phone turned sideways, a tablet —
        // puts the board on the left at the full height and everything else in a column to
        // its right. Stacked, the header and the controls would take that height from the
        // board and push it partly off screen.
        val boardEdge = maxHeight
        if (maxWidth - boardEdge >= SIDE_PANE_MIN_WIDTH.dp) {
            Row(Modifier.fillMaxSize()) {
                board(Modifier.size(boardEdge))
                ScreenColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // Only this column makes room for the keyboard: the board keeps its size.
                        .imePadding(),
                ) {
                    header()
                    controls()
                }
            }
        } else {
            ScreenColumn(
                Modifier
                    .fillMaxSize()
                    // The comment field sits low on the screen: keep it above the keyboard.
                    .imePadding(),
            ) {
                header()
                // The board is square, so it has to fit the *short* edge of the window. Sizing
                // it to the width is right in portrait but makes it taller than the viewport on
                // anything wide, where it would simply overflow into the scroll. Centred, and
                // capped so a strip of the header and the move navigation stays visible.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    board(Modifier.widthIn(max = maxBoardEdge()))
                }
                controls()
            }
        }
    }

    if (showingInfo) {
        GameInfoSheet(
            state = state,
            serverName = viewModel.serverName,
            gameUrl = viewModel.gameUrl,
            // The sheet closes first: both actions ask for confirmation, and a dialog behind
            // a sheet is unreachable.
            onPass = {
                showingInfo = false
                viewModel.askPass()
            },
            onResign = {
                showingInfo = false
                viewModel.askResign()
            },
            onOpenWeb = { uriHandler.openUri(it) },
            onDismiss = { showingInfo = false },
        )
    }

    when (state.confirmation) {
        PendingConfirmation.PASS -> {
            Confirmation(
                title = stringResource(R.string.board_pass_question),
                // A pass needs no explaining, except that a second one ends the game — and,
                // where counting happens on the web, sends the user there to finish it.
                text =
                    when {
                        !state.opponentPassed -> null
                        viewModel.scoresInApp -> stringResource(R.string.board_pass_ends_game)
                        else -> stringResource(R.string.board_pass_ends_game_web, viewModel.serverName)
                    },
                confirmLabel = stringResource(R.string.board_pass),
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
                comment = state.confirmationComment,
                onCommentChange = viewModel::onConfirmationCommentChange,
            )
        }

        PendingConfirmation.RESIGN -> {
            Confirmation(
                title = stringResource(R.string.board_resign_question),
                text = null,
                confirmLabel = stringResource(R.string.board_resign),
                confirmColor = colors.urgent,
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
                comment = state.confirmationComment,
                onCommentChange = viewModel::onConfirmationCommentChange,
            )
        }

        PendingConfirmation.SEND_MARKS -> {
            Confirmation(
                title = stringResource(R.string.score_send_marks_question),
                text = stringResource(R.string.score_send_marks_explain),
                confirmLabel = stringResource(R.string.score_send_marks),
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
            )
        }

        PendingConfirmation.ACCEPT_SCORE -> {
            Confirmation(
                title = stringResource(R.string.score_accept_question),
                text = stringResource(R.string.score_accept_explain),
                confirmLabel = stringResource(R.string.score_accept),
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
            )
        }

        null -> {
            // No dialog on screen.
        }
    }
}

/**
 * A scrolling column at least as tall as the room it is given, so that a weighted spacer in it
 * can push what follows to the bottom edge. A plain scrolling column measures its content with
 * no height limit, which leaves weights nothing to share out.
 */
@Composable
private fun ScreenColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            content = content,
        )
    }
}

/** "You passed", "kaya_r resigned" — the line under the board, in the fewest words. */
@Composable
private fun boardEventLabel(
    event: BoardEvent,
    isYou: Boolean,
    opponent: String,
): String =
    when (event.kind) {
        BoardEventKind.PASS -> {
            if (isYou) {
                stringResource(R.string.board_you_passed)
            } else {
                stringResource(R.string.board_they_passed, opponent)
            }
        }

        BoardEventKind.RESIGNATION -> {
            if (isYou) {
                stringResource(R.string.board_you_resigned)
            } else {
                stringResource(R.string.board_they_resigned, opponent)
            }
        }

        BoardEventKind.TIMEOUT -> {
            if (isYou) {
                stringResource(R.string.board_you_timed_out)
            } else {
                stringResource(R.string.board_they_timed_out, opponent)
            }
        }

        BoardEventKind.FORFEIT -> {
            if (isYou) {
                stringResource(R.string.board_you_forfeited)
            } else {
                stringResource(R.string.board_they_forfeited, opponent)
            }
        }
    }

/**
 * Move navigation: start, back, the cursor's move number, forward, end of trial. Five
 * outlined pills, borrowing the action row's button shape so they read as controls rather
 * than as status glyphs. The trial's depth needs no separate label: it is the numbered stones
 * on the board and this counter.
 */
@Composable
private fun MoveNavigationRow(
    state: BoardUiState,
    viewModel: BoardViewModel,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavButton(
            enabled = state.canGoBack,
            description = stringResource(R.string.board_first_move),
            onClick = viewModel::toStart,
        ) { NavIcon(NavShape.SKIP_BACK, it) }
        RepeatNavButton(
            enabled = state.canGoBack,
            description = stringResource(R.string.board_previous_move),
            onStep = viewModel::stepBack,
        ) { NavIcon(NavShape.STEP_BACK, it) }
        CurrentMoveButton(
            cursor = state.cursor,
            atCurrent = state.atCurrent,
            onClick = viewModel::toCurrent,
        )
        RepeatNavButton(
            enabled = state.canGoForward,
            description = stringResource(R.string.board_next_move),
            onStep = viewModel::stepForward,
        ) { NavIcon(NavShape.STEP_FWD, it) }
        NavButton(
            enabled = state.canGoForward,
            description = stringResource(R.string.board_last_move),
            onClick = viewModel::toEnd,
        ) { NavIcon(NavShape.SKIP_FWD, it) }
    }
}

/**
 * "You're winning by 7.5", "A draw", "No result" — the count as a sentence, from the seat of
 * the person holding the phone. Every game in the app is one of theirs, so there is always a
 * side to speak from, and naming a colour here would make the reader do the translation.
 *
 * Present tense, and "winning" rather than "leads": the game is not over until both players
 * have agreed, and "leads" belongs to a game still being played.
 */
@Composable
private fun leadLabel(
    result: GameResult?,
    you: StoneColor,
): String {
    val winner = result?.winner
    return when {
        result == null -> {
            stringResource(R.string.score_no_result)
        }

        result.by == ResultReason.NO_RESULT -> {
            stringResource(R.string.score_no_result)
        }

        winner == null -> {
            stringResource(R.string.score_draw)
        }

        else -> {
            stringResource(
                if (winner == you) R.string.score_winning else R.string.score_losing,
                Formatting.points(result.margin ?: 0.0),
            )
        }
    }
}

/** "you by 6.5", "kirisame by 6.5", "a draw" — a result inside a sentence, not opening one. */
@Composable
private fun asideLabel(
    result: GameResult,
    you: StoneColor,
    opponent: String,
): String {
    val margin = Formatting.points(result.margin ?: 0.0)
    return when (result.winner) {
        null -> stringResource(R.string.score_aside_draw)
        you -> stringResource(R.string.score_aside_you, margin)
        else -> stringResource(R.string.score_aside_them, opponent, margin)
    }
}

/** "black" / "white", for the colour that follows a player's name in the breakdown. */
@Composable
private fun colorName(color: StoneColor): String =
    stringResource(if (color == StoneColor.BLACK) R.string.score_black else R.string.score_white)

/**
 * What the two sums are made of, named in the order they are added: "Territory + prisoners
 * + komi". Only the addends that actually appear are named, so a game with no komi and no
 * prisoners does not promise figures that are not on the screen.
 */
@Composable
private fun countKey(count: ScoreCount): String {
    val parts =
        buildList {
            add(stringResource(R.string.score_key_territory))
            val secondary =
                if (count.rules == ScoringRules.AREA) {
                    count.stonesOf(StoneColor.BLACK) + count.stonesOf(StoneColor.WHITE)
                } else {
                    count.capturesOf(StoneColor.BLACK) + count.capturesOf(StoneColor.WHITE)
                }
            if (secondary > 0) {
                add(
                    stringResource(
                        if (count.rules ==
                            ScoringRules.AREA
                        ) {
                            R.string.score_key_stones
                        } else {
                            R.string.score_key_prisoners
                        },
                    ),
                )
            }
            if (count.komi != 0.0) add(stringResource(R.string.score_key_komi))
            if (count.handicapCompensation != 0.0) add(stringResource(R.string.score_key_handicap))
        }
    return parts.joinToString(" + ")
}

/**
 * One side of the breakdown: who they are, the addends, and what they come to. A side with
 * a single addend shows the figure once — "0 = 0" is arithmetic nobody needs checking.
 */
@Composable
private fun ScoreLine(
    name: String,
    addends: List<Double>,
    total: Double,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (addends.size > 1) {
            Text(
                addends.joinToString(" + ") { Formatting.points(it) },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
            )
            Text(
                stringResource(R.string.score_sum, Formatting.points(total)),
                Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text(
                Formatting.points(total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The count, in the space the navigation row usually occupies: whether the reader is winning
 * and by how much, what each side's total is made of, and one line of guidance about what to
 * do next.
 *
 * The headline figure is the server's own while the marks are untouched — it is the count an
 * agreement agrees to — and the device's own count as soon as the user marks something,
 * because the server's answer is then about a different set of stones.
 *
 * The breakdown underneath is always the device's own arithmetic: the server sends a result
 * and its territory, but no per-side totals, so there is nothing of its own to take apart.
 * On the rare position where the two answers differ, a line says so rather than leaving two
 * numbers on screen that refuse to add up.
 */
@Composable
private fun ScorePanel(
    scoring: ScoringUiState,
    you: StoneColor,
    opponent: String,
    canMark: Boolean,
    waitingForOpponent: Boolean,
    onReset: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // "Reset marks" shares a line with the headline only. The breakdown keeps the full
        // width underneath, so its figures stay in the same column whether or not the action
        // is there — the totals must not jump sideways the moment the user marks a group.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                leadLabel(scoring.result, you),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // Only offered once there is something to undo; tapping a group again also works.
            // Until then it is laid out invisibly: the action is taller than the headline, and
            // the panel must not grow — pushing everything below it down — on the first mark.
            if (scoring.changed) {
                TextAction(stringResource(R.string.score_reset), onReset, color = colors.accent)
            } else {
                TextAction(
                    stringResource(R.string.score_reset),
                    onClick = {},
                    modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                    enabled = false,
                )
            }
        }
        val count = scoring.count
        if (count != null) {
            // The reader's own side first: the screen is about their game.
            for (color in listOf(you, you.opposite)) {
                ScoreLine(
                    name =
                        if (color == you) {
                            stringResource(R.string.score_side_you, colorName(color))
                        } else {
                            stringResource(R.string.score_side_them, opponent, colorName(color))
                        },
                    addends = count.addendsOf(color),
                    total = count.pointsOf(color),
                )
            }
            Text(
                countKey(count),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2,
            )
        }
        scoring.localAside?.let {
            Text(
                stringResource(R.string.score_server_differs, asideLabel(it, you, opponent)),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
            )
        }
        val hint =
            when {
                scoring.guessed -> stringResource(R.string.score_hint_guessed)
                scoring.changed -> stringResource(R.string.score_hint_changed)
                waitingForOpponent -> stringResource(R.string.score_hint_waiting)
                canMark -> stringResource(R.string.score_hint_mark)
                else -> null
            }
        if (hint != null) {
            // Two lines held even for a one-line hint, so swapping hints on the first mark
            // does not move whatever sits below the panel.
            Text(
                hint,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                minLines = 2,
            )
        }
    }
}

private enum class NavShape { SKIP_BACK, STEP_BACK, STEP_FWD, SKIP_FWD }

/**
 * A move-navigation icon drawn as a filled triangle (media-transport style): a single
 * triangle for a step, a triangle plus an end bar for a jump to the start or the end.
 */
@Composable
private fun NavIcon(
    shape: NavShape,
    tint: Color,
) {
    val forward = shape == NavShape.STEP_FWD || shape == NavShape.SKIP_FWD
    val withBar = shape == NavShape.SKIP_BACK || shape == NavShape.SKIP_FWD
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val triW = w * 0.30f
        val triH = h * 0.52f
        val barW = w * 0.085f
        val gap = w * 0.05f
        val groupW = if (withBar) triW + gap + barW else triW
        val left = (w - groupW) / 2f

        // Where the triangle sits within the centered group (bar leads on a back-skip).
        val triLeft = if (withBar && !forward) left + barW + gap else left
        val triRight = triLeft + triW
        val triangle =
            Path().apply {
                if (forward) {
                    moveTo(triLeft, cy - triH / 2f)
                    lineTo(triRight, cy)
                    lineTo(triLeft, cy + triH / 2f)
                } else {
                    moveTo(triRight, cy - triH / 2f)
                    lineTo(triLeft, cy)
                    lineTo(triRight, cy + triH / 2f)
                }
                close()
            }
        drawPath(triangle, tint)

        if (withBar) {
            val barLeft = if (forward) triRight + gap else left
            drawRoundRect(
                color = tint,
                topLeft = Offset(barLeft, cy - triH / 2f),
                size = Size(barW, triH),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** Height of a move-navigation pill: below the 48dp action row, which outranks it. */
private val NAV_PILL_HEIGHT = 44.dp

/**
 * The frame every move-navigation control shares: an outlined pill in the action row's
 * shape, so the row reads as five buttons.
 *
 * [highlighted] is the counter's live state — the board showing exactly the server position.
 * It is not an action and cannot be pressed, but it is the *good* state, so it is filled
 * rather than dimmed: accent on [colors.accentSoft], the strongest thing in the row.
 * Disabled controls keep the pill and fade the border instead of disappearing, so the row
 * never changes shape as the cursor moves.
 *
 * Feedback on press is the fill, not a ripple: flat, and identical for the plain buttons and
 * the hold-to-repeat ones, which cannot use [clickable].
 */
@Composable
private fun RowScope.NavPill(
    enabled: Boolean,
    pressed: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable (tint: Color) -> Unit,
) {
    val border =
        when {
            highlighted -> colors.accent
            enabled -> colors.line
            else -> colors.line.copy(alpha = 0.5f)
        }
    val fill =
        when {
            highlighted -> colors.accentSoft
            pressed -> colors.line
            else -> Color.Transparent
        }
    val tint =
        when {
            highlighted -> colors.accent
            enabled -> colors.ink
            else -> colors.line
        }
    Box(
        modifier
            .weight(1f)
            .height(NAV_PILL_HEIGHT)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(1.dp, border, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

/** A single move-navigation button: jumps once per tap. */
@Composable
private fun RowScope.NavButton(
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    NavPill(
        enabled = enabled,
        pressed = pressed,
        description = description,
        modifier =
            if (enabled) {
                Modifier.clickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
            } else {
                Modifier
            },
        content = content,
    )
}

/**
 * The cursor's move number, and the way back to the live position.
 *
 * [atCurrent] means the board is showing exactly what the server has: the counter equals the
 * played move count *and* no variation branches out of the past. There is then nothing for a
 * tap to do — but that is the state the whole screen is about, so it is highlighted rather
 * than disabled. Anywhere else the same control is an ordinary button that returns there.
 */
@Composable
private fun RowScope.CurrentMoveButton(
    cursor: Int,
    atCurrent: Boolean,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    NavPill(
        enabled = true,
        pressed = pressed && !atCurrent,
        highlighted = atCurrent,
        description =
            if (atCurrent) {
                stringResource(R.string.board_at_current_move, cursor)
            } else {
                stringResource(R.string.board_back_to_current)
            },
        modifier =
            if (atCurrent) {
                Modifier
            } else {
                Modifier.clickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
            },
    ) { tint ->
        Text(
            cursor.toString(),
            fontSize = 18.sp,
            fontWeight = if (atCurrent) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
        )
    }
}

// Held-button replay speed. A 19x19 game runs past 200 moves, so a fixed slow repeat makes
// crossing one a chore. The repeat instead accelerates: it opens slow enough to stop on a
// chosen move, then winds up to a rate that crosses a long game in a few seconds.

/**
 * The narrowest the column beside the board may be: the header and the Skip / Send row stay
 * legible at this width. A window that cannot give it stacks the board above the controls.
 */
private const val SIDE_PANE_MIN_WIDTH = 220

/** Vertical space the board leaves for the header, the event line and the navigation row. */
private const val BOARD_HEIGHT_ALLOWANCE = 104

/** Below this the board would be unreadable; better to overflow than to shrink past it. */
private const val MIN_BOARD_EDGE = 240

/**
 * The largest square the board may occupy. The width wins in portrait, so the board stays
 * full width exactly as before; in landscape — or any window wider than it is tall — the
 * height wins and the board shrinks to fit instead of running off the bottom.
 */
@Composable
private fun maxBoardEdge(): Dp {
    val window = LocalConfiguration.current
    val limitedByHeight = window.screenHeightDp - BOARD_HEIGHT_ALLOWANCE
    return minOf(window.screenWidthDp, limitedByHeight).coerceAtLeast(MIN_BOARD_EDGE).dp
}

private const val REPEAT_HOLD_MS = 350L
private const val REPEAT_FIRST_MS = 110L
private const val REPEAT_FASTEST_MS = 35L
private const val REPEAT_RAMP_STEPS = 12

/**
 * A move-navigation button that steps once on tap and, if held, replays in sequence: the
 * first step is immediate, then after [REPEAT_HOLD_MS] it repeats, accelerating from
 * [REPEAT_FIRST_MS] to [REPEAT_FASTEST_MS] per move over [REPEAT_RAMP_STEPS] steps.
 */
@Composable
private fun RowScope.RepeatNavButton(
    enabled: Boolean,
    description: String,
    onStep: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    // Compose hands the gesture block to the pointer-input node once and leaves it there: the
    // node is rebuilt only when pointerInput's keys change, and `Unit` never does. So the block
    // must not close over [onStep] itself. Skipping to the next game swaps the view model under
    // a node that survives the swap, and the captured lambda would go on stepping the game the
    // user just left — invisibly, which reads on screen as a button that has stopped working.
    // Through rememberUpdatedState it always steps the game currently shown.
    val step by rememberUpdatedState(onStep)
    NavPill(
        enabled = enabled,
        pressed = pressed,
        description = description,
        modifier =
            if (!enabled) {
                Modifier
            } else {
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        step()
                        val repeat =
                            scope.launch {
                                delay(REPEAT_HOLD_MS)
                                var stepsSoFar = 0
                                while (true) {
                                    step()
                                    delay(repeatDelayMs(stepsSoFar))
                                    stepsSoFar++
                                }
                            }
                        // The repeat runs in the composition's scope, not the gesture's, so it
                        // outlives a gesture that is cancelled rather than ended — and that is
                        // the ordinary case here, because the last step to move 0 or to the end
                        // of the line disables this button and detaches the node under the
                        // finger. Left running it would step for the life of the screen, pinning
                        // the cursor at that end and leaving the opposite button unusable.
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            pressed = false
                            repeat.cancel()
                        }
                    }
                }
            },
        content = content,
    )
}

/** Linear ramp from [REPEAT_FIRST_MS] down to [REPEAT_FASTEST_MS]; flat after the ramp. */
private fun repeatDelayMs(stepsSoFar: Int): Long {
    val progress = (stepsSoFar.toFloat() / REPEAT_RAMP_STEPS).coerceAtMost(1f)
    return (REPEAT_FIRST_MS + (REPEAT_FASTEST_MS - REPEAT_FIRST_MS) * progress).toLong()
}

/**
 * The game's name plate, and — once the record is loaded — the way into the game-info sheet.
 * The disclosure chevron is the only affordance: it is navigation, not one of the named text
 * actions the board screen otherwise insists on, and it costs the board no vertical space.
 */
@Composable
private fun Header(
    server: ServerId,
    opponent: String,
    subtitle: String,
    remaining: RemainingTime?,
    you: StoneColor,
    /** Non-null once the game is over; it then replaces the clock. */
    result: GameResult? = null,
    ratingChange: Double? = null,
    isFinished: Boolean = false,
    onOpenInfo: (() -> Unit)? = null,
) {
    val openInfo = stringResource(R.string.info_open)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onOpenInfo != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onOpenInfo)
                        .semantics { contentDescription = openInfo }
                } else {
                    Modifier
                },
            ).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Which server the game is on, in the mark the games list uses.
                ServerDot(server, Modifier.padding(end = 8.dp))
                Text(opponent, style = MaterialTheme.typography.titleMedium)
                if (onOpenInfo != null) Chevron(Modifier.padding(start = 6.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isFinished) {
                // "Won by 12.5" in the corner the clock had, with what it did to the rating
                // under it — the two things a player looks up about a game that is over.
                OutcomeLabel(result, you, ratingChange)
            } else {
                RemainingTimeLabel(remaining)
                Text(
                    stringResource(R.string.board_to_move),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink2,
                )
            }
        }
    }
}

/** A small disclosure chevron: "there is more behind this". */
@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    val tint = colors.ink2
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.38f, h * 0.28f)
                lineTo(w * 0.64f, h * 0.5f)
                lineTo(w * 0.38f, h * 0.72f)
            },
            tint,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The comments at the cursor, each under its writer's name: "You" or the opponent's handle,
 * never a colour, as everywhere else the board speaks from the reader's seat. A part not
 * everyone can read yet says so, because its writer is the one person who sees it and
 * should not mistake it for said.
 */
@Composable
private fun Comments(
    comments: List<GameComment>,
    you: StoneColor,
    opponent: String,
    finished: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        comments.forEachIndexed { index, comment ->
            // One writer's parts in a row read as one message, so the name is not repeated.
            val sameWriter = index > 0 && comments[index - 1].by == comment.by
            Column {
                if (!sameWriter) {
                    Text(
                        if (comment.by == you) stringResource(R.string.board_comment_you) else opponent,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                }
                Text(comment.text, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                commentAudience(comment.visibility, finished)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
                }
            }
        }
    }
}

/** Only the audiences worth a warning; a hidden part stops being hidden when the game ends. */
@Composable
private fun commentAudience(
    visibility: CommentVisibility,
    finished: Boolean,
): String? =
    when (visibility) {
        CommentVisibility.HIDDEN_UNTIL_END -> {
            if (finished) null else stringResource(R.string.comment_hidden_until_end)
        }

        CommentVisibility.SECRET_UNTIL_END -> {
            if (finished) null else stringResource(R.string.comment_secret_until_end)
        }

        CommentVisibility.WRITER_ONLY -> {
            stringResource(R.string.comment_writer_only)
        }

        CommentVisibility.EVERYONE, CommentVisibility.PLAYERS -> {
            null
        }
    }

/**
 * A yes-or-no before an act that is not a stone. [text] is for what the title cannot say on
 * its own, and is left out when there is nothing. With [onCommentChange], the dialog also
 * holds the [comment] that goes with the act: pass and resign are reached from the info
 * sheet, where the board's own comment field is out of sight.
 */
@Composable
private fun Confirmation(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = colors.accent,
    comment: String = "",
    onCommentChange: ((String) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text =
            if (text == null && onCommentChange == null) {
                null
            } else {
                {
                    Column {
                        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (onCommentChange != null) {
                            if (text != null) Spacer(Modifier.height(8.dp))
                            TextField(
                                value = comment,
                                onValueChange = onCommentChange,
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                placeholder = {
                                    Text(
                                        stringResource(R.string.board_confirm_comment_label),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.ink2,
                                    )
                                },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                colors = commentFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = confirmColor) } },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) { Text(stringResource(R.string.board_cancel), color = colors.ink) }
        },
    )
}

/** An underlined one-line field, growing to a few lines for a longer message. */
@Composable
private fun CommentField(
    value: String,
    onChange: (String) -> Unit,
    focus: Boolean,
    onBlur: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focus) { if (focus) focusRequester.requestFocus() }
    // Only a real loss of focus counts: the field reports "unfocused" once as it appears,
    // before the request above has landed.
    var hadFocus by remember { mutableStateOf(false) }
    // The label is the placeholder, on the field's own line: one row instead of two, and
    // once there is text the text says what the field is.
    Column(modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = onChange,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(
                    stringResource(R.string.board_comment_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink2,
                )
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = commentFieldColors(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (hadFocus && !it.isFocused) onBlur()
                        hadFocus = it.isFocused
                    },
        )
    }
}

/** Underlined on a transparent ground, as the board's comment field and the dialogs' share. */
@Composable
private fun commentFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = colors.accent,
        unfocusedIndicatorColor = colors.line,
        cursorColor = colors.accent,
        focusedTextColor = colors.ink,
        unfocusedTextColor = colors.ink,
    )
