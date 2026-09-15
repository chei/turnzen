package dev.turnzen.app.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.common.RemainingTimeLabel
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.PlayerDetail
import dev.turnzen.goserver.TournamentRef

/**
 * What the board behind the sheet cannot say for itself: who is playing and how they stand,
 * how long the game has been running, what it is being played for, and the rare actions —
 * pass and resign. Not everything the server sent: settings fixed when the game was created
 * are answered once and then only take up room.
 *
 * A sheet rather than a screen: it is reading material *about* the board behind it, which
 * stays visible, and dismissing it is a swipe. It also keeps the hand-rolled navigation at
 * two destinations plus an open game.
 *
 * Resign lives here because it is irreversible and wants to be hard to reach by accident.
 * Pass lives here too, but unlike resign it is a move, so it is also promoted into the action
 * row when the opponent has passed — see [BoardUiState.passPromoted].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameInfoSheet(
    state: BoardUiState,
    serverName: String,
    gameUrl: String?,
    onPass: () -> Unit,
    onResign: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = state.headerSummary ?: return
    val info = state.detail?.info
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // How much of the top of the screen the status bar (and any cutout) takes.
    val topInset =
        WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top)
            .asPaddingValues()
            .calculateTopPadding()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.ink,
        // Material's default content insets pad the top by the status bar *minus the sheet's
        // own offset*, which makes the content's height depend on where the sheet sits. The
        // sheet derives its drag anchors from that height, so a fling up — which overshoots
        // past the top anchor — moves the anchors, and moving the anchors restarts the
        // settling animation with the original fling velocity. That never converges: the sheet
        // oscillates until the next touch cancels it. So take no insets from the sheet, pad
        // the bottom ourselves, and keep the sheet clear of the status bar by height instead.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        BoxWithConstraints {
            Column(
                Modifier
                    .heightIn(max = maxHeight - topInset)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            ) {
                Text(
                    stringResource(R.string.info_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))

                // Both players, each with their own clock — the board header can only show the
                // clock of whoever is to move, and in correspondence the other one matters too.
                val toMove = state.detail?.record?.nextToMove
                PlayerLine(
                    color = StoneColor.BLACK,
                    player = info?.black,
                    fallbackName = nameFor(state, StoneColor.BLACK),
                    isYou = summary.myColor == StoneColor.BLACK,
                    isToMove = toMove == StoneColor.BLACK,
                )
                Hairline()
                PlayerLine(
                    color = StoneColor.WHITE,
                    player = info?.white,
                    fallbackName = nameFor(state, StoneColor.WHITE),
                    isYou = summary.myColor == StoneColor.WHITE,
                    isToMove = toMove == StoneColor.WHITE,
                )

                Spacer(Modifier.height(20.dp))

                // Only what a player cannot see for themselves on the board behind the sheet.
                // Komi, rules, rated, the time system and the weekend clock were all here and
                // are all fixed at game creation: they answer a question nobody asks twice.
                val size = state.boardSize
                if (size != null) InfoRow(stringResource(R.string.info_board), "${size}x$size")
                val handicap = info?.handicap ?: summary.handicap
                if (handicap > 0) InfoRow(stringResource(R.string.info_handicap), handicap.toString())
                // How long it has been running, which is what the start date is really asked for.
                info?.startedAt?.let { InfoRow(stringResource(R.string.info_started), Formatting.since(it)) }
                // What the game is part of. Only when the server actually described the game:
                // a reopen with no network knows nothing, and "waiting room" would be a guess.
                if (info != null) EventRows(info.tournament, state.ladderStanding)

                Spacer(Modifier.height(20.dp))

                if (state.canPass) {
                    ActionLine(stringResource(R.string.board_pass), colors.accent, onPass)
                }
                if (gameUrl != null) {
                    ActionLine(
                        stringResource(R.string.info_open_web, serverName),
                        colors.accent,
                    ) { onOpenWeb(gameUrl) }
                }
                // Last, and the only urgent thing in the sheet: giving the game up.
                ActionLine(stringResource(R.string.board_resign), colors.urgent, onResign)

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The handle to show for a side when the server sent no game info (an offline reopen). */
private fun nameFor(
    state: BoardUiState,
    color: StoneColor,
): String {
    val summary = state.headerSummary ?: return "?"
    return if (summary.myColor == color) {
        state.detail
            ?.record
            ?.let { if (color == StoneColor.BLACK) it.black.name else it.white.name }
            .orEmpty()
            .ifEmpty { "?" }
    } else {
        summary.opponent.handle
    }
}

/**
 * One side of the game: stone, who they are, how they stand, and their clock. The clock is
 * muted for the side not to move, exactly as a their-turn row is muted in the games list.
 */
@Composable
private fun PlayerLine(
    color: StoneColor,
    player: PlayerDetail?,
    fallbackName: String,
    isYou: Boolean,
    isToMove: Boolean,
) {
    val name = player?.handle ?: fallbackName
    val details =
        buildList {
            if (isYou) add(stringResource(R.string.info_you))
            player?.prisoners?.let { add(stringResource(R.string.info_captures, it)) }
            // Only worth a word when the rank actually moved during the game.
            player?.rankAtStart?.takeIf { it != player.rank }?.let {
                add(stringResource(R.string.info_was_rank, it))
            }
        }.joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoneIcon(color, 18.dp, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                Formatting.opponent(name, player?.rank),
                style = MaterialTheme.typography.titleMedium,
            )
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
            }
        }
        RemainingTimeLabel(player?.remainingTime, muted = !isToMove)
    }
}

/**
 * Where the game came from, in one row: a ladder, another tournament, or neither.
 *
 * A DGS ladder is named by its number — "#3" is the 19x19 one everybody means — so the id
 * belongs in the line and not only in the URL behind it. For a casual game the row names both
 * of the ways one starts, because DGS keeps nothing on the game that tells a waiting-room
 * pairing from an accepted invitation.
 *
 * The event's kind ("Dragon Ladder", "Private Round Robin") is deliberately not shown: for a
 * ladder it repeats the label, and for anything else it repeats the tournament's own name.
 */
@Composable
private fun EventRows(
    tournament: TournamentRef?,
    standing: LadderStanding?,
) {
    if (tournament == null) {
        InfoRow(stringResource(R.string.info_game), stringResource(R.string.info_game_casual))
        return
    }
    InfoRow(
        stringResource(if (tournament.isLadder) R.string.info_ladder else R.string.info_tournament),
        tournament.name
            ?.let { stringResource(R.string.info_event_named, tournament.id, it) }
            ?: stringResource(R.string.info_event_id, tournament.id),
    )
    val round = tournament.round
    val rounds = tournament.roundCount
    // "Round 1 of 1" is what every ladder challenge says, and says nothing.
    if (round != null && rounds != null && rounds > 1) {
        InfoRow(stringResource(R.string.info_round), stringResource(R.string.info_round_of, round, rounds))
    }
    // Appears a moment after the rest: it is the one row that costs a request. Appended
    // rather than inserted, so nothing above it moves when it lands.
    standing?.let {
        InfoRow(
            stringResource(R.string.info_standing),
            stringResource(
                if (it.youAreChallenger) {
                    R.string.info_standing_challenging
                } else {
                    R.string.info_standing_defending
                },
                it.yourRank,
                it.opponentRank,
            ),
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink2,
            )
        }
        Hairline()
    }
}

/** A full-width action, in the Settings screen's row vocabulary. */
@Composable
private fun ActionLine(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Hairline()
    }
}
