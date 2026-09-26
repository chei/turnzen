package dev.turnzen.app.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.common.OutcomeLabel
import dev.turnzen.app.ui.common.ProblemMessage
import dev.turnzen.app.ui.common.ProblemState
import dev.turnzen.app.ui.common.RemainingTimeLabel
import dev.turnzen.app.ui.common.ServerBar
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.PendingAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    viewModel: GamesViewModel,
    tab: GamesTab,
    onTabChange: (GamesTab) -> Unit,
    onOpenGame: (GameId) -> Unit,
    /** Where a server's lapsed sign-in is put right: the account panels in Settings. */
    onSignInAgain: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val games = state.listFor(tab)
    val arrivals = state.arrivalsFor(tab)

    // Refresh every time the list is shown: on app start, on returning from a game, and on
    // resume after the app was in the background. Tied to the resumed state rather than to
    // composition so that a game finished while the app was away is not still listed.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnShow()
        onPauseOrDispose {}
    }

    // The archive is fetched the first time it is looked at, not with every refresh: a
    // finished game does not change, so there is nothing to poll for.
    LaunchedEffect(tab) { viewModel.onTabShown(tab) }

    // One scroll position per tab: the lists differ in length, and a single shared
    // LazyListState re-anchors on its keyed items when the tab switches, which could leave
    // the other list scrolled to the bottom. Each tab now keeps and restores its own place.
    val yourTurnListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val finishedListState = rememberLazyListState()
    val listState =
        when (tab) {
            GamesTab.YOUR_TURN -> yourTurnListState
            GamesTab.ALL -> allListState
            GamesTab.FINISHED -> finishedListState
        }

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
                onClick = { onTabChange(GamesTab.YOUR_TURN) },
            )
            GamesTab(
                label = stringResource(R.string.games_tab_all),
                count = state.allRunning?.size,
                selected = tab == GamesTab.ALL,
                onClick = { onTabChange(GamesTab.ALL) },
            )
            // No count: this tab shows the last few finished games rather than all of them,
            // so any number next to it would count what happened to fit on the screen.
            GamesTab(
                label = stringResource(R.string.games_tab_finished),
                count = null,
                selected = tab == GamesTab.FINISHED,
                onClick = { onTabChange(GamesTab.FINISHED) },
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing && games != null,
            onRefresh = { viewModel.refresh(tab) },
            modifier = Modifier.fillMaxSize(),
        ) {
            // A list per tab, not one list re-filled: a game on both "Your turn" and "All
            // games" keeps its key across a tab switch, and without this the row would
            // animate from its place in one list to its place in the other. The scroll
            // position survives, since each tab's LazyListState is held outside this.
            key(tab) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    val problem = state.problem
                    when {
                        // Nothing has ever loaded, so the problem is the whole screen: give
                        // it the empty state's shape and an explicit Try again, rather than a
                        // line of red text over an otherwise blank list.
                        games == null && problem != null -> {
                            item { ProblemState(problem, onRetry = { viewModel.refresh(tab) }) }
                        }

                        else -> {
                            // A list is on screen, so a problem is only why it — or one
                            // server's part of it — has not changed; one line each above it,
                            // and pull-to-refresh is the retry. A server that signed the user
                            // out says so, with the way back in.
                            state.allProblems.forEach { line ->
                                item {
                                    val signedOut = line in state.signedOut
                                    ProblemMessage(
                                        line,
                                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                        onRetry = if (signedOut) onSignInAgain else null,
                                        retryLabel =
                                            if (signedOut) {
                                                stringResource(
                                                    R.string.games_sign_in_again,
                                                )
                                            } else {
                                                null
                                            },
                                    )
                                }
                            }
                            when {
                                games == null -> {
                                    item { LoadingRow() }
                                }

                                games.isEmpty() -> {
                                    item { EmptyState(tab) }
                                }

                                else -> {
                                    // Keyed by server too: both servers number their games.
                                    items(games, key = { "${it.id.server.value}:${it.id.value}" }) { game ->
                                        GameRow(
                                            game = game,
                                            serverName = viewModel.serverName(game.id),
                                            arrived = game.id in arrivals,
                                            onArrivalShown = { viewModel.onArrivalShown(game.id) },
                                            onClick = {
                                                // A board Turnzen cannot draw opens where it can be played.
                                                if (game.onlyOnWeb) {
                                                    viewModel.gameUrl(game.id)?.let(uriHandler::openUri)
                                                } else {
                                                    onOpenGame(game.id)
                                                }
                                            },
                                            // Placement only. A row that appears or leaves
                                            // does so at once: with a fade here, every row
                                            // would also fade in on arriving at the screen
                                            // and on every tab switch, on top of the
                                            // navigation transition already playing.
                                            modifier =
                                                Modifier.animateItem(
                                                    fadeInSpec = null,
                                                    fadeOutSpec = null,
                                                    placementSpec =
                                                        tween(
                                                            SLIDE_MILLIS,
                                                            easing = FastOutSlowInEasing,
                                                        ),
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
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
    val color = if (selected) colors.accent else colors.ink2
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
        HorizontalDivider(thickness = 2.dp, color = if (selected) colors.accent else colors.line)
    }
}

@Composable
private fun LoadingRow() {
    Text(
        "Checking your games…",
        Modifier.fillMaxWidth().padding(20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
    )
}

@Composable
private fun EmptyState(tab: GamesTab) {
    val title =
        when (tab) {
            GamesTab.YOUR_TURN -> stringResource(R.string.games_empty_title)
            GamesTab.ALL -> stringResource(R.string.games_empty_all_title)
            GamesTab.FINISHED -> stringResource(R.string.games_empty_finished_title)
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
                color = colors.ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * How long the wash over a new row takes to arrive, and how long it takes to leave. It is
 * deliberately lopsided: the colour appears faster than the eye can follow, then drains
 * slowly enough to be watched on the way out, which is what points at the row. A flash of
 * equal halves reads as a rendering glitch instead — and one too short to catch is over
 * before a gaze that started elsewhere can reach it.
 *
 * Both are scaled by the system animation setting, so turning animations off skips this.
 */
private const val FLASH_IN_MILLIS = 120
private const val FLASH_OUT_MILLIS = 700

/**
 * How long the rows under a new one take to move out of its way. Long enough to be read as
 * the list making room — a row arriving with no warning at the top, shoving everything down
 * between two frames, is the thing that makes a refreshed list hard to follow — and short
 * enough to be over while the new row is still lit: the movement hands the eye to the
 * colour rather than competing with it.
 */
private const val SLIDE_MILLIS = 300

@Composable
private fun GameRow(
    game: GameSummary,
    serverName: String,
    arrived: Boolean,
    onArrivalShown: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A row a background refresh brought in lights up once, then fades back into the page.
    // It plays on arrival and is reported back as played, so it never repeats on the next
    // look at the list.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(arrived) {
        if (!arrived) return@LaunchedEffect
        flash.animateTo(1f, tween(FLASH_IN_MILLIS, easing = LinearEasing))
        flash.animateTo(0f, tween(FLASH_OUT_MILLIS, easing = LinearEasing))
        onArrivalShown()
    }
    val flashColor = colors.accentSoft

    // Opaque, not transparent over the window: rows are drawn in list order, so a row
    // sliding down is drawn over the row arriving above it and uncovers it on the way, and
    // the list reads as making room. Left see-through, the two would ghost through each
    // other for the length of the slide.
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onClick)
            // The colour bar says which server this is to the eye; this says it to TalkBack.
            .semantics { stateDescription = "on $serverName" },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                // Read in the draw phase rather than as composable state: the fade then
                // repaints the row without recomposing or re-laying-out anything. Placed
                // before the padding, so the wash runs the full width of the screen.
                .drawBehind { if (flash.value > 0f) drawRect(flashColor, alpha = flash.value) },
        ) {
            // The server's mark sits in the margin, so the text keeps its column whether one
            // server is signed in or two.
            ServerBar(game.id.server, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
            GameRowContent(game)
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Hairline() }
    }
}

@Composable
private fun GameRowContent(game: GameSummary) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                Formatting.opponent(game.opponent.handle, game.opponent.rank),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${Formatting.boardSize(game.boardSize, game.handicap, game.boardHeight)}, " +
                    (
                        when {
                            // A board this app cannot draw: say where it can be seen.
                            game.onlyOnWeb -> stringResource(R.string.games_on_the_web)

                            // A server that does not say how long a finished game ran
                            // (OGS's game list) gets no count rather than "0 moves".
                            game.isFinished && game.moveNumber == 0 -> stringResource(R.string.games_finished)

                            // A game that is over is not at a move, it had that many.
                            game.isFinished -> stringResource(R.string.games_moves, game.moveNumber)

                            // A game being counted is not waiting for a move either, and
                            // saying "move 22" would send the user looking for one.
                            game.pendingAction == PendingAction.SCORE -> stringResource(R.string.games_counting)

                            else -> stringResource(R.string.games_move, game.moveNumber)
                        }
                    ) + ", ${Formatting.relative(game.lastMoveAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        // A finished game has no clock to show; what it has instead is how it went.
        if (game.isFinished) {
            OutcomeLabel(game.result, game.myColor, game.ratingChange)
        } else {
            RemainingTimeLabel(game.remainingTime, muted = !game.isMyTurn)
        }
    }
}
