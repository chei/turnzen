package dev.turnzen.app.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.data.BoardSettings
import dev.turnzen.app.ui.common.ErrorMessage
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.RemainingTimeLabel
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.common.TextAction
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
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.headerSummary
    // Draw the real position once loaded; while it loads, an empty grid at the known board
    // size, so the board keeps its final position instead of flashing a blank rectangle.
    val position = state.position ?: state.boardSize?.let { Position.empty(it) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TextAction(
            "←  " + stringResource(R.string.board_back),
            onClick = onBack,
            color = colors.ink2,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )

        if (summary != null) {
            val moveNumber = if (state.detail != null) state.moveCount else summary.moveNumber
            Header(
                myColor = summary.myColor,
                opponent = Formatting.opponent(summary.opponent.handle, summary.opponent.rank),
                subtitle =
                    "You play ${if (summary.myColor == StoneColor.BLACK) "black" else "white"}, " +
                        "move $moveNumber",
                remaining = summary.remainingTime,
            )
        } else {
            Spacer(Modifier.height(60.dp))
        }

        if (position != null) {
            GoBoard(
                position = position,
                trialMoves = state.trialMoves,
                settings = boardSettings,
                // No interaction with the placeholder grid until the real position loads.
                enabled = !state.sending && !state.loading,
                onTap = viewModel::onTap,
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(colors.board))
        }

        Spacer(Modifier.height(20.dp))

        // Move navigation: start, back, current server state (shown as the live move
        // number), forward, end of trial. The trial's depth needs no separate label:
        // it is the numbered stones on the board and this counter.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavButton(enabled = state.canGoBack, onClick = viewModel::toStart) { NavIcon(NavShape.SKIP_BACK, it) }
            RepeatNavButton(enabled = state.canGoBack, onStep = viewModel::stepBack) { NavIcon(NavShape.STEP_BACK, it) }
            NavButton(enabled = true, onClick = viewModel::toCurrent) { tint ->
                Text(state.cursor.toString(), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = tint)
            }
            RepeatNavButton(
                enabled = state.canGoForward,
                onStep = viewModel::stepForward,
            ) { NavIcon(NavShape.STEP_FWD, it) }
            NavButton(enabled = state.canGoForward, onClick = viewModel::toEnd) { NavIcon(NavShape.SKIP_FWD, it) }
        }

        state.error?.let {
            ErrorMessage(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
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
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
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

/** A single move-navigation button: jumps once per tap. */
@Composable
private fun RowScope.NavButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content(if (enabled) colors.ink else colors.line)
    }
}

// Held-button replay speed. A 19x19 game runs past 200 moves, so a fixed slow repeat makes
// crossing one a chore. The repeat instead accelerates: it opens slow enough to stop on a
// chosen move, then winds up to a rate that crosses a long game in a few seconds.
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
    onStep: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
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
                            repeat.cancel()
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(if (enabled) colors.ink else colors.line)
    }
}

/** Linear ramp from [REPEAT_FIRST_MS] down to [REPEAT_FASTEST_MS]; flat after the ramp. */
private fun repeatDelayMs(stepsSoFar: Int): Long {
    val progress = (stepsSoFar.toFloat() / REPEAT_RAMP_STEPS).coerceAtMost(1f)
    return (REPEAT_FIRST_MS + (REPEAT_FASTEST_MS - REPEAT_FIRST_MS) * progress).toLong()
}

@Composable
private fun Header(
    myColor: StoneColor,
    opponent: String,
    subtitle: String,
    remaining: RemainingTime?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoneIcon(myColor, 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(opponent, style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun Confirmation(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.ink2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = colors.accent) } },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) { Text(stringResource(R.string.board_cancel), color = colors.ink) }
        },
    )
}
