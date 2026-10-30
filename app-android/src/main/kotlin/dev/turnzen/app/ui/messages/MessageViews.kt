package dev.turnzen.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.DOT_INSET
import dev.turnzen.app.ui.common.ErrorMessage
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.PlayerIcon
import dev.turnzen.app.ui.common.ServerDot
import dev.turnzen.app.ui.common.iconIsSquare
import dev.turnzen.app.ui.common.serverColor
import dev.turnzen.app.ui.games.MessageRow
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.MessageTopic

/** The opponent's picture on a game row is this size too, so the two kinds of row line up. */
private const val ICON_SIZE = 36

/** The heading over the new-messages block: it sets the messages apart from the games under them. */
@Composable
fun MessagesHeading(count: Int) {
    Text(
        stringResource(R.string.messages_heading, count),
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = colors.ink2,
    )
}

/** The heading over the games when the messages stand above them. */
@Composable
fun GamesHeading() {
    Text(
        stringResource(R.string.messages_games_heading),
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = colors.ink2,
    )
}

/**
 * One new message, shaped like a game row so the block reads as part of the same list: the
 * server's mark in the margin, who it is from, what it is about, and how long ago it came.
 */
@Composable
fun MessageListRow(
    row: MessageRow,
    serverName: String,
    showIcon: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onClick)
            .semantics { stateDescription = "message on $serverName" },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            ServerDot(row.message.id.server, Modifier.align(Alignment.CenterStart).padding(start = DOT_INSET))
            Row(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    SenderIcon(row, serverName)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(
                            row.text.title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            Formatting.relative(row.message.sentAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink2,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    row.text.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Hairline() }
    }
}

/**
 * The person the message is from or about — the writer, or the opponent in the game a server
 * message concerns — so a challenge from hana_go carries hana_go's picture, as their game
 * row does. A server message about nobody the app knows gets the server's colour, blank, so
 * it is not mistaken for a player whose name begins with its initial.
 */
@Composable
private fun SenderIcon(
    row: MessageRow,
    serverName: String,
) {
    val person = row.person
    if (person == null) {
        Box(
            Modifier
                .size(ICON_SIZE.dp)
                .clip(if (iconIsSquare(row.message.id.server)) RoundedCornerShape(4.dp) else CircleShape)
                .background(serverColor(row.message.id.server).copy(alpha = 0.35f)),
        )
        return
    }
    PlayerIcon(
        url = person.iconUrl,
        name = person.handle,
        server = row.message.id.server,
        size = ICON_SIZE.dp,
    )
}

/**
 * The open message: all of it, and what can be done about it. Every act is a button of its
 * own, because each one changes the message on the server too; reading it here changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSheet(
    row: MessageRow,
    serverName: String,
    busy: Boolean,
    error: String?,
    onOpenGame: (GameId) -> Unit,
    onOpenWeb: (String) -> Unit,
    onMarkRead: () -> Unit,
    onReply: (String) -> Unit,
    onAnswerInvitation: (accept: Boolean, text: String?) -> Unit,
    onClose: () -> Unit,
) {
    val message = row.message
    val topic = message.topic
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val topInset =
        WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top)
            .asPaddingValues()
            .calculateTopPadding()
    var draft by rememberSaveable(message.id.value) { mutableStateOf("") }
    val openInvitation = topic is MessageTopic.GameInvitation && topic.settings != null && message.awaitsAnswer

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        // As the game info sheet: no insets from the sheet itself, see GameInfoSheet.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        BoxWithConstraints {
            Column(
                Modifier
                    .heightIn(max = maxHeight - topInset)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                // A player's message is headed by its subject: who wrote it is the line below.
                val headline =
                    if (topic == MessageTopic.Conversation) {
                        message.subject.trim().ifEmpty { row.text.title }
                    } else {
                        row.text.title
                    }
                Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServerDot(message.id.server, Modifier.padding(end = 8.dp))
                    val from = message.from?.let { Formatting.opponent(it.handle, it.rank) }
                    Text(
                        listOfNotNull(from, serverName, Formatting.relative(message.sentAt)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink2,
                    )
                }
                Spacer(Modifier.height(16.dp))

                when (topic) {
                    MessageTopic.Conversation -> {
                        Text(message.text.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyLarge)
                    }

                    is MessageTopic.GameInvitation -> {
                        row.text.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        message.text.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "${message.from?.handle ?: "They"}: $it",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.ink2,
                            )
                        }
                    }

                    else -> {
                        row.text.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }

                val canWrite = openInvitation || (topic == MessageTopic.Conversation && message.canReply)
                if (canWrite) {
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = !busy,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = {
                            Text(
                                stringResource(
                                    if (openInvitation) {
                                        R.string.messages_answer_label
                                    } else {
                                        R.string.messages_reply_label
                                    },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.ink2,
                            )
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    ErrorMessage(it)
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val half = Modifier.weight(1f)
                    when {
                        openInvitation -> {
                            PillButton(
                                stringResource(R.string.messages_decline),
                                { onAnswerInvitation(false, draft.ifBlank { null }) },
                                half,
                                enabled = !busy,
                            )
                            PillButton(
                                stringResource(R.string.messages_accept),
                                { onAnswerInvitation(true, draft.ifBlank { null }) },
                                half,
                                primary = true,
                                enabled = !busy,
                            )
                        }

                        canWrite -> {
                            PillButton(stringResource(R.string.messages_mark_read), onMarkRead, half, enabled = !busy)
                            PillButton(
                                stringResource(R.string.messages_send_reply),
                                { onReply(draft) },
                                half,
                                primary = true,
                                enabled = !busy && draft.isNotBlank(),
                            )
                        }

                        row.game != null -> {
                            PillButton(stringResource(R.string.messages_mark_read), onMarkRead, half, enabled = !busy)
                            PillButton(
                                stringResource(R.string.messages_open_game),
                                { onOpenGame(row.game) },
                                half,
                                primary = true,
                                enabled = !busy,
                            )
                        }

                        row.webUrl != null -> {
                            PillButton(stringResource(R.string.messages_mark_read), onMarkRead, half, enabled = !busy)
                            PillButton(
                                stringResource(R.string.messages_open_web),
                                { onOpenWeb(row.webUrl) },
                                half,
                                primary = true,
                            )
                        }

                        else -> {
                            PillButton(
                                stringResource(R.string.messages_mark_read),
                                onMarkRead,
                                half,
                                primary = true,
                                enabled = !busy,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** Underlined on a transparent ground, as the board's comment field. */
@Composable
private fun fieldColors() =
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
