package dev.turnzen.app.ui.board

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.common.TextAction
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor
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
    val detail = state.detail
    val position = state.position

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

        if (detail != null) {
            Header(
                myColor = detail.summary.myColor,
                opponent = Formatting.opponent(detail.summary.opponent.handle, detail.summary.opponent.rank),
                subtitle =
                    "You play ${if (detail.summary.myColor == StoneColor.BLACK) "black" else "white"}, " +
                        "move ${state.moveCount}",
                remaining = Formatting.remaining(detail.summary.remainingTime),
                urgent = Formatting.isUrgent(detail.summary.remainingTime),
            )
        } else {
            Spacer(Modifier.height(60.dp))
        }

        if (position != null) {
            GoBoard(
                position = position,
                trialMoves = state.trialMoves,
                settings = boardSettings,
                enabled = !state.sending,
                onTap = viewModel::onTap,
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(colors.board))
        }

        Spacer(Modifier.height(16.dp))

        // Info line: trial status with Undo/Reset, or a context hint.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.hasTrial) {
                Box(Modifier.size(10.dp).background(colors.accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Trial, ${state.trialCount} ${if (state.trialCount == 1) "move" else "moves"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                TextAction(stringResource(R.string.board_undo), onClick = viewModel::undo)
                Spacer(Modifier.width(20.dp))
                TextAction(stringResource(R.string.board_reset), onClick = viewModel::reset)
            } else {
                Text(
                    when {
                        state.notice != null -> state.notice.orEmpty()
                        !state.isMyTurn -> stringResource(R.string.board_opponent_to_move)
                        state.atEnd -> stringResource(R.string.board_hint_try)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.notice != null) colors.accent else colors.ink2,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Move navigation: start, back, current server state, forward, end of trial.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavButton("«", enabled = state.canGoBack, onClick = viewModel::toStart)
            RepeatNavButton("‹", enabled = state.canGoBack, onStep = viewModel::stepBack)
            NavButton("0", enabled = true, onClick = viewModel::toCurrent)
            RepeatNavButton("›", enabled = state.canGoForward, onStep = viewModel::stepForward)
            NavButton("»", enabled = state.canGoForward, onClick = viewModel::toEnd)
        }

        state.error?.let {
            ErrorMessage(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        // Skip is always available; Send only on the user's own turn. On the opponent's
        // turn the board is just for exploring their move, so Skip stands alone.
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
                            state.sending -> "Sending…"
                            state.sendLabel != null -> "${stringResource(R.string.board_send)} ${state.sendLabel}"
                            else -> stringResource(R.string.board_send)
                        },
                    onClick = viewModel::askSend,
                    primary = true,
                    enabled = state.canSend,
                    modifier = Modifier.weight(1f),
                )
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

/** A single move-navigation glyph: jumps once per tap. */
@Composable
private fun RowScope.NavButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 22.sp, color = if (enabled) colors.ink else colors.line)
    }
}

/**
 * A move-navigation glyph that steps once on tap and, if held, replays in sequence:
 * the first step is immediate, then after ~0.9s it repeats about every 0.5s until release.
 */
@Composable
private fun RowScope.RepeatNavButton(
    glyph: String,
    enabled: Boolean,
    onStep: () -> Unit,
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
                                    delay(900)
                                    while (true) {
                                        onStep()
                                        delay(500)
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
        Text(glyph, fontSize = 22.sp, color = if (enabled) colors.ink else colors.line)
    }
}

@Composable
private fun Header(
    myColor: StoneColor,
    opponent: String,
    subtitle: String,
    remaining: String,
    urgent: Boolean,
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
            Text(
                remaining,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (urgent) colors.urgent else colors.ink,
            )
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
