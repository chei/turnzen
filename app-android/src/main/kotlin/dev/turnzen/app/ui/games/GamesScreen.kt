package dev.turnzen.app.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.ErrorMessage
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.theme.Tokens
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    viewModel: GamesViewModel,
    onOpenGame: (GameId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(GamesTab.YOUR_TURN) }
    val games = state.listFor(tab)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            GamesTab(
                label = stringResource(R.string.games_tab_your_turn),
                count = state.yourTurn?.size,
                selected = tab == GamesTab.YOUR_TURN,
                onClick = { tab = GamesTab.YOUR_TURN },
            )
            GamesTab(
                label = stringResource(R.string.games_tab_all),
                count = state.allRunning?.size,
                selected = tab == GamesTab.ALL,
                onClick = { tab = GamesTab.ALL },
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing && games != null,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    item { ErrorMessage(error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                }
                when {
                    games == null -> item { LoadingRow(state.error == null) }
                    games.isEmpty() -> item { EmptyState(tab) }
                    else -> items(games, key = { it.id.value }) { game -> GameRow(game) { onOpenGame(game.id) } }
                }
            }
        }
    }
}

@Composable
private fun RowScope.GamesTab(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) Tokens.accent else Tokens.ink2
    Column(Modifier.weight(1f).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = color,
            )
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
        HorizontalDivider(thickness = 2.dp, color = if (selected) Tokens.accent else Tokens.line)
    }
}

@Composable
private fun LoadingRow(loading: Boolean) {
    if (loading) {
        Text(
            "Checking your games…",
            Modifier.fillMaxWidth().padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.ink2,
        )
    }
}

@Composable
private fun EmptyState(tab: GamesTab) {
    val title =
        if (tab == GamesTab.YOUR_TURN) {
            stringResource(R.string.games_empty_title)
        } else {
            stringResource(R.string.games_empty_all_title)
        }
    Column(
        Modifier.fillMaxWidth().padding(top = 160.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StoneIcon(StoneColor.WHITE, 44.dp)
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        if (tab == GamesTab.YOUR_TURN) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.games_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GameRow(
    game: GameSummary,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StoneIcon(game.myColor, 22.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    Formatting.opponent(game.opponent.handle, game.opponent.rank),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${Formatting.boardSize(game.boardSize, game.handicap)}, " +
                        "move ${game.moveNumber}, ${Formatting.relative(game.lastMoveAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (game.isMyTurn) {
                Text(
                    Formatting.remaining(game.remainingTime),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (Formatting.isUrgent(game.remainingTime)) Tokens.urgent else Tokens.ink,
                )
            } else {
                Text(
                    stringResource(R.string.games_their_move),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.ink2,
                )
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Hairline() }
    }
}
