package dev.turnzen.app.ui.board

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.data.BoardSettings
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.ProblemMessage
import dev.turnzen.app.ui.common.RemainingTimeLabel
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.Position
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.RemainingTime
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // No back control of its own: Android's back (gesture or button) closes the game, as
        // does the Games tab in the bottom bar. A third affordance only cost the board the
        // vertical space it sat in.
        if (summary != null) {
            val moveNumber = if (state.detail != null) state.moveCount else summary.moveNumber
            Header(
                opponent = Formatting.opponent(summary.opponent.handle, summary.opponent.rank),
                subtitle =
                    "You play ${if (summary.myColor == StoneColor.BLACK) "black" else "white"}, " +
                        "move $moveNumber",
                remaining = summary.remainingTime,
                // The header names the game, so it is also the way into everything else about
                // it. Only once the record is here: the sheet's actions need its move token.
                onOpenInfo = if (state.detail != null) ({ showingInfo = true }) else null,
            )
        } else {
            Spacer(Modifier.height(60.dp))
        }

        // The board is square, so it has to fit the *short* edge of the window. Sizing it to
        // the width is right in portrait but makes it taller than the viewport in landscape
        // (and on anything wide), where it would simply overflow into the scroll. Centred,
        // and capped so a strip of the header and the move navigation stays visible.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val boardWidth = Modifier.widthIn(max = maxBoardEdge())
            if (position != null) {
                GoBoard(
                    position = position,
                    modifier = boardWidth,
                    trialMoves = state.trialMoves,
                    settings = boardSettings,
                    // No interaction with the placeholder grid until the real position loads.
                    enabled = !state.sending && !state.loading,
                    onTap = viewModel::onTap,
                )
            } else {
                Box(boardWidth.fillMaxWidth().aspectRatio(1f).background(colors.board))
            }
        }

        Spacer(Modifier.height(20.dp))

        // Move navigation: start, back, the cursor's move number, forward, end of trial.
        // Five outlined pills, borrowing the action row's button shape so they read as
        // controls rather than as status glyphs. The trial's depth needs no separate label:
        // it is the numbered stones on the board and this counter.
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

        // Skip keeps a fixed half-width slot on the left in every game; Send fills the
        // right slot only on the user's own turn (an empty spacer holds it otherwise), so
        // Skip never changes size or position.
        Spacer(Modifier.height(8.dp))
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
            if (state.isMyTurn) {
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
                                    "Sending…"
                                }

                                state.canSend && state.sendLabel != null -> {
                                    "${stringResource(R.string.board_send)} ${state.sendLabel}"
                                }

                                else -> {
                                    stringResource(R.string.board_send)
                                }
                            },
                        onClick = viewModel::askSend,
                        primary = true,
                        enabled = state.canSend,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
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
        PendingConfirmation.SEND -> {
            Confirmation(
                title = "${stringResource(R.string.board_send)} ${state.sendLabel}?",
                text = stringResource(R.string.board_send_explain),
                confirmLabel = "${stringResource(R.string.board_send)} ${state.sendLabel}",
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
            )
        }

        PendingConfirmation.PASS -> {
            Confirmation(
                title = stringResource(R.string.board_pass_question),
                // Passing after the opponent has passed ends the game and starts scoring,
                // which this app cannot do yet — say so before the move, not after it.
                text =
                    if (state.opponentPassed) {
                        stringResource(R.string.board_pass_explain_after_pass, viewModel.serverName)
                    } else {
                        stringResource(R.string.board_pass_explain)
                    },
                confirmLabel = stringResource(R.string.board_pass),
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
            )
        }

        PendingConfirmation.RESIGN -> {
            Confirmation(
                title = stringResource(R.string.board_resign_question),
                text = stringResource(R.string.board_resign_explain),
                confirmLabel = stringResource(R.string.board_resign),
                confirmColor = colors.urgent,
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
            )
        }

        null -> {
            // No dialog on screen.
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

/** Vertical space the board leaves for the header and move navigation around it. */
private const val BOARD_HEIGHT_ALLOWANCE = 96

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
                        onStep()
                        val repeat =
                            scope.launch {
                                delay(REPEAT_HOLD_MS)
                                var stepsSoFar = 0
                                while (true) {
                                    onStep()
                                    delay(repeatDelayMs(stepsSoFar))
                                    stepsSoFar++
                                }
                            }
                        waitForUpOrCancellation()
                        pressed = false
                        repeat.cancel()
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
    opponent: String,
    subtitle: String,
    remaining: RemainingTime?,
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
                Text(opponent, style = MaterialTheme.typography.titleMedium)
                if (onOpenInfo != null) Chevron(Modifier.padding(start = 6.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RemainingTimeLabel(remaining)
            Text(
                stringResource(R.string.board_to_move),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
            )
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

@Composable
private fun Confirmation(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = colors.accent,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = confirmColor) } },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) { Text(stringResource(R.string.board_cancel), color = colors.ink) }
        },
    )
}
