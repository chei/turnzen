package dev.turnzen.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.data.MoveOutbox
import dev.turnzen.app.data.OutgoingMove
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.goserver.GameId

/**
 * One line above the bottom bar about the move that has just left: "Sending D4 to kaya_r ·
 * Undo" while it waits out its undo window, and "D4 to kaya_r wasn't sent · Open" if it did
 * not go through. On every screen, because by then the board it was sent from has stepped on.
 *
 * Flat like the rest of the app: a hairline above, no card and no shadow. It sits in the
 * layout rather than over it, so it never covers the next game's Send button.
 */
@Composable
fun OutboxLine(
    outbox: MoveOutbox,
    onOpen: (GameId) -> Unit,
) {
    val waiting by outbox.waiting.collectAsStateWithLifecycle()
    val failed by outbox.failed.collectAsStateWithLifecycle()
    val pending = waiting
    val back = failed
    when {
        pending != null -> {
            Line(
                text = sendingText(pending),
                action = stringResource(R.string.outbox_undo),
                onAction = { outbox.undo()?.let { onOpen(it.gameId) } },
            )
        }

        back != null -> {
            Line(
                text = failedText(back.move),
                action = stringResource(R.string.outbox_open),
                onAction = { onOpen(back.move.gameId) },
            )
        }
    }
}

@Composable
private fun sendingText(move: OutgoingMove): String =
    move.label?.let { stringResource(R.string.outbox_sending, it, move.opponent) }
        ?: stringResource(R.string.outbox_sending_pass, move.opponent)

@Composable
private fun failedText(move: OutgoingMove): String =
    move.label?.let { stringResource(R.string.outbox_failed, it, move.opponent) }
        ?: stringResource(R.string.outbox_failed_pass, move.opponent)

@Composable
private fun Line(
    text: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextAction(action, onAction, Modifier.padding(start = 12.dp), color = colors.accent)
        }
    }
}
