package dev.turnzen.app.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.data.BoardSettings
import dev.turnzen.app.ui.common.ErrorMessage
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.common.TextAction
import dev.turnzen.app.ui.theme.Tokens
import dev.turnzen.game.StoneColor

@Composable
fun BoardScreen(
    viewModel: BoardViewModel,
    boardSettings: BoardSettings,
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
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Tokens.board))
        }

        Spacer(Modifier.height(24.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isTrial) {
                Box(Modifier.size(10.dp).background(Tokens.accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Trial, ${state.trialMoves.size} ${if (state.trialMoves.size == 1) "move" else "moves"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Tokens.accent,
                    modifier = Modifier.weight(1f),
                )
                TextAction(stringResource(R.string.board_undo), onClick = viewModel::undo)
                Spacer(Modifier.width(20.dp))
                TextAction(stringResource(R.string.board_reset), onClick = viewModel::reset)
            } else {
                TextAction("‹", onClick = viewModel::showPrevious, color = Tokens.ink2, enabled = state.viewingMove > 0)
                Text(
                    state.viewingMove.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextAction("›", onClick = viewModel::showNext, color = Tokens.ink2, enabled = !state.atLatest)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        state.notice != null -> state.notice.orEmpty()
                        !state.atLatest -> "Viewing move ${state.viewingMove} of ${state.moveCount}"
                        else -> stringResource(R.string.board_hint_try)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.notice != null) Tokens.accent else Tokens.ink2,
                )
            }
        }

        state.error?.let {
            ErrorMessage(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton(
                text = stringResource(R.string.board_pass),
                onClick = viewModel::askPass,
                enabled = state.canPass,
                modifier = Modifier.weight(1f),
            )
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

        PendingConfirmation.PASS -> {
            Confirmation(
                title = stringResource(R.string.board_pass_confirm),
                text = stringResource(R.string.board_pass_explain),
                confirmLabel = stringResource(R.string.board_pass),
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirmation,
            )
        }

        null -> {
            Unit
        }
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
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Tokens.ink2)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                remaining,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (urgent) Tokens.urgent else Tokens.ink,
            )
            Text(
                stringResource(R.string.board_to_move),
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.ink2,
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
        containerColor = Tokens.surface,
        titleContentColor = Tokens.ink,
        textContentColor = Tokens.ink2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Tokens.accent) } },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) { Text(stringResource(R.string.board_cancel), color = Tokens.ink) }
        },
    )
}
