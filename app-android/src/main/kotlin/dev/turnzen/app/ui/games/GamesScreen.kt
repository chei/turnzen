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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.common.OutcomeLabel
import dev.turnzen.app.ui.common.PlayerIcon
import dev.turnzen.app.ui.common.ProblemMessage
import dev.turnzen.app.ui.common.ProblemState
import dev.turnzen.app.ui.common.RemainingTimeLabel
import dev.turnzen.app.ui.common.ServerBar
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.common.scrollIndicator
import dev.turnzen.app.ui.messages.GamesHeading
import dev.turnzen.app.ui.messages.MessageListRow
import dev.turnzen.app.ui.messages.MessageSheet
import dev.turnzen.app.ui.messages.MessagesHeading
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.PendingAction
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    viewModel: GamesViewModel,
    tab: GamesTab,
    onTabChange: (GamesTab) -> Unit,
    onOpenGame: (GameId) -> Unit,
    /** Where a server's lapsed sign-in is put right: the account panels in Settings. */
    onSignInAgain: () -> Unit = {},
    /** Opponents' icons on the rows (a setting, on by default). */
    showPlayerIcons: Boolean = true,
    /** A message to open, as a tapped notification asked; [onMessageOpened] once it is. */
    messageToOpen: MessageId? = null,
    onMessageOpened: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(messageToOpen) {
        // Opened by id: on a cold start the sheet appears once the refresh brings the message.
        messageToOpen?.let(viewModel::openMessage)
        if (messageToOpen != null) onMessageOpened()
    }

    // Refresh every time the list is shown: on app start, on returning from a game, and on
    // resume after the app was in the background. Tied to the resumed state rather than to
    // composition so that a game finished while the app was away is not still listed.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnShow()
        onPauseOrDispose { viewModel.onHidden() }
    }

    // One scroll position per tab: the lists differ in length, and a single shared
    // LazyGridState re-anchors on its keyed items when the tab switches, which could leave
    // the other list scrolled to the bottom. Each tab now keeps and restores its own place.
    val yourTurnListState = rememberLazyGridState()
    val allListState = rememberLazyGridState()
    val finishedListState = rememberLazyGridState()

    fun listStateFor(page: GamesTab) =
        when (page) {
            GamesTab.YOUR_TURN -> yourTurnListState
            GamesTab.ALL -> allListState
            GamesTab.FINISHED -> finishedListState
        }

    // The tabs are pages side by side, so a sideways swipe moves to the next list. The page
    // the pager comes to rest on is reported back as [tab], which the shell holds (Skip and
    // auto-advance step through that list); a tapped tab scrolls the pager there, and so
    // does a [tab] set from outside, such as by a notification that asks for "Your turn".
    // A plain remember rather than rememberPagerState, which is saved: a page restored from
    // before the list was last shown could disagree with [tab] and win.
    val pagerState = remember { PagerState(currentPage = tab.ordinal) { GamesTab.entries.size } }
    val scope = rememberCoroutineScope()

    fun showTab(target: GamesTab) {
        scope.launch { pagerState.animateScrollToPage(target.ordinal) }
    }

    LaunchedEffect(tab) {
        if (pagerState.targetPage != tab.ordinal) pagerState.animateScrollToPage(tab.ordinal)
    }
    val currentOnTabChange by rememberUpdatedState(onTabChange)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { currentOnTabChange(GamesTab.entries[it]) }
    }
    // The label in the accent colour is the page a swipe is heading for, not the settled
    // one, so a tapped tab lights up at once rather than passing over the tabs in between.
    // The underline below it moves with the finger instead, drawn under the row.
    val shownTab = GamesTab.entries[pagerState.targetPage]

    // No wordmark above the tabs: it cost a fifth of a landscape screen to repeat the name
    // the launcher already showed, and the tabs say which list this is.
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val accent = colors.accent
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .drawWithContent {
                    drawContent()
                    // Where the pager is, in pages, mid-swipe included: the underline slides
                    // under the tabs with the finger, the way Material tabs do. Read while
                    // drawing, so a swipe redraws this line rather than recomposing the row.
                    val position =
                        (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                            .coerceIn(0f, (GamesTab.entries.size - 1).toFloat())
                    val tabWidth = size.width / GamesTab.entries.size
                    val thickness = TAB_UNDERLINE.dp.toPx()
                    drawRect(
                        accent,
                        topLeft = Offset(position * tabWidth, size.height - thickness),
                        size = Size(tabWidth, thickness),
                    )
                },
        ) {
            GamesTab(
                label = stringResource(R.string.games_tab_your_turn),
                count = state.yourTurn?.size,
                selected = shownTab == GamesTab.YOUR_TURN,
                onClick = { showTab(GamesTab.YOUR_TURN) },
            )
            GamesTab(
                label = stringResource(R.string.games_tab_all),
                count = state.allRunning?.size,
                selected = shownTab == GamesTab.ALL,
                onClick = { showTab(GamesTab.ALL) },
            )
            // No count: this tab shows the last few finished games rather than all of them,
            // so any number next to it would count what happened to fit on the screen.
            GamesTab(
                label = stringResource(R.string.games_tab_finished),
                count = null,
                selected = shownTab == GamesTab.FINISHED,
                onClick = { showTab(GamesTab.FINISHED) },
            )
        }

        // Compose's own page snapping, deliberately left at its defaults (a page turns once
        // it is half way over, or on a fling): the behaviour Android users expect, rather
        // than a threshold tuned by feel.
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            // A list per tab, not one list re-filled: a game on both "Your turn" and "All
            // games" keeps its key across a tab switch, and in a shared list the row would
            // animate from its place in one to its place in the other. The scroll position
            // survives a page leaving the screen, since each tab's LazyGridState is held
            // above the pager.
            val pageTab = GamesTab.entries[page]
            GamesPage(
                viewModel = viewModel,
                state = state,
                tab = pageTab,
                listState = listStateFor(pageTab),
                onOpenGame = onOpenGame,
                onSignInAgain = onSignInAgain,
                showPlayerIcons = showPlayerIcons,
            )
        }
    }

    // The open message, over whichever tab is showing.
    state.openRow?.let { row ->
        val uriHandler = LocalUriHandler.current
        MessageSheet(
            row = row,
            serverName = viewModel.serverName(row.message.id.server),
            busy = state.messageBusy,
            error = state.messageError,
            onOpenGame = { id ->
                // Opening the game is what the message was for: it is dealt with.
                viewModel.closeMessage()
                viewModel.dismissMessage(row.message.id)
                onOpenGame(id)
            },
            onOpenWeb = uriHandler::openUri,
            onMarkRead = { viewModel.dismissMessage(row.message.id) },
            onReply = { viewModel.reply(row.message.id, it) },
            onAnswerInvitation = { accept, text -> viewModel.answerInvitation(row.message.id, accept, text) },
            onClose = viewModel::closeMessage,
        )
    }
}

/** One tab's list: a page of the pager on the games screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamesPage(
    viewModel: GamesViewModel,
    state: GamesUiState,
    tab: GamesTab,
    listState: LazyGridState,
    onOpenGame: (GameId) -> Unit,
    onSignInAgain: () -> Unit,
    showPlayerIcons: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    val games = state.listFor(tab)
    val arrivals = state.arrivalsFor(tab)

    // The archive is fetched the first time it is looked at, not with every refresh: a
    // finished game does not change, so there is nothing to poll for. A page is composed
    // as soon as a swipe brings it into view, so the fetch starts before the swipe ends.
    LaunchedEffect(tab) { viewModel.onTabShown(tab) }

    // The rows the list draws. It trails [games] so that a new game is never slipped in
    // somewhere out of sight: the list is first taken to its top, and only then does the new
    // row go in, pushing the others down while it lights up. That happens wherever the list
    // was scrolled to, since a new game is what the user should look at next; any other
    // change goes straight in, and a list scrolled down keeps its place. A tab opened with
    // new rows waiting starts without them, so they come in the same way.
    var shown by remember { mutableStateOf(games?.filterNot { it.id in arrivals }) }
    LaunchedEffect(listState, games) {
        val shownIds = shown?.mapTo(HashSet()) { it.id }
        if (shownIds != null && games.orEmpty().any { it.id in arrivals && it.id !in shownIds }) {
            listState.animateScrollToItem(0)
        }
        shown = games
    }

    // A list at its top stays at its top when rows go in above the first one. Left alone,
    // the LazyColumn keeps the row that was first in view where it is, which pushes the new
    // row up out of sight. Run on apply, before the layout that takes in the new rows, so
    // the request replaces that anchoring. It asks for the position the list already has,
    // so the rows below still slide down rather than jump. The problem lines go in above the
    // first row too: a pull-to-refresh takes them down and a failed one puts them back, which
    // would otherwise land them just above the screen on a list long enough to scroll.
    DisposableEffect(listState, shown, state.allProblems) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
            listState.requestScrollToItem(0)
        }
        onDispose {}
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing && games != null,
        onRefresh = { viewModel.refresh(tab) },
        modifier = Modifier.fillMaxSize(),
    ) {
        // A grid rather than a column so that a wide window — a phone turned sideways —
        // shows games side by side instead of stretching each row across the screen and
        // fitting three. A portrait phone is narrower than two columns, so it keeps one.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = MIN_COLUMN_WIDTH.dp),
            state = listState,
            modifier = Modifier.fillMaxSize().scrollIndicator(listState),
        ) {
            val problem = state.problem
            val rows = shown
            when {
                // Nothing has ever loaded, so the problem is the whole screen: give
                // it the empty state's shape and an explicit Try again, rather than a
                // line of red text over an otherwise blank list.
                rows == null && problem != null -> {
                    item(span = fullLine) { ProblemState(problem, onRetry = { viewModel.refresh(tab) }) }
                }

                else -> {
                    // A list is on screen, so a problem is only why it — or one
                    // server's part of it — has not changed; one line each above it,
                    // and pull-to-refresh is the retry. A server that signed the user
                    // out says so, with the way back in.
                    state.allProblems.forEach { line ->
                        item(span = fullLine) {
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
                    // The new messages lead "Your turn", as on the DGS status page: they say
                    // why a game has appeared in the list below, or gone from it.
                    val messages = if (tab == GamesTab.YOUR_TURN) state.messages else emptyList()
                    if (messages.isNotEmpty()) {
                        item(span = fullLine, key = "messages-heading") { MessagesHeading(messages.size) }
                        items(
                            messages,
                            key = { "message:${it.message.id.server.value}:${it.message.id.value}" },
                            span = { fullLine() },
                        ) { row ->
                            MessageListRow(
                                row = row,
                                serverName = viewModel.serverName(row.message.id.server),
                                showIcon = showPlayerIcons,
                                onClick = { viewModel.openMessage(row.message.id) },
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                            )
                        }
                    }
                    // Under the messages, a heading of the games' own, so the two kinds of row
                    // (they are shaped alike) are not read as one list.
                    if (messages.isNotEmpty() && !rows.isNullOrEmpty()) {
                        item(span = fullLine, key = "games-heading") { GamesHeading() }
                    }
                    when {
                        rows == null -> {
                            item(span = fullLine) { LoadingRow() }
                        }

                        rows.isEmpty() -> {
                            item(span = fullLine) { EmptyState(tab, compact = messages.isNotEmpty()) }
                        }

                        else -> {
                            // Keyed by server too: both servers number their games.
                            items(rows, key = { "${it.id.server.value}:${it.id.value}" }) { game ->
                                GameRow(
                                    game = game,
                                    serverName = viewModel.serverName(game.id),
                                    showIcon = showPlayerIcons,
                                    mark = state.markFor(tab, game),
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
        // The selected tab's accent underline is drawn over this by the row, so it can slide.
        HorizontalDivider(thickness = TAB_UNDERLINE.dp, color = colors.line)
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
private fun EmptyState(
    tab: GamesTab,
    /** Messages stand above it: no need to bring it down to the middle of the screen. */
    compact: Boolean = false,
) {
    val title =
        when (tab) {
            GamesTab.YOUR_TURN -> stringResource(R.string.games_empty_title)
            GamesTab.ALL -> stringResource(R.string.games_empty_all_title)
            GamesTab.FINISHED -> stringResource(R.string.games_empty_finished_title)
        }
    Column(
        Modifier.fillMaxWidth().padding(top = if (compact) 48.dp else 160.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StoneIcon(StoneColor.WHITE, 44.dp)
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
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

/** A row narrower than this squeezes the clock into the opponent's name; two fit side by side in landscape. */
private const val MIN_COLUMN_WIDTH = 340

/** The line under the tabs, in dp: grey across all three, accent under the selected one. */
private const val TAB_UNDERLINE = 2

/** The opponent's picture: two lines of text tall, so the row keeps its height. */
private const val ICON_SIZE = 36

/** Messages and the empty state run across every column. */
private val fullLine: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

@Composable
private fun GameRow(
    game: GameSummary,
    serverName: String,
    showIcon: Boolean,
    mark: RowMark,
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
    // A game just begun flashes in its own green and drains to its tint; a blue flash over
    // a green row read as the wrong colour.
    val flashColor = if (mark == RowMark.BEGUN) colors.rowBegunFlash else colors.accentSoft
    // A game not opened since it began, or since it ended, keeps a quiet tint until it is:
    // unlike the flash, it says something about the game, not about the last refresh.
    val background =
        when (mark) {
            RowMark.BEGUN -> colors.rowBegun
            RowMark.ENDED -> colors.rowEnded
            RowMark.NONE -> colors.surface
        }
    val markText =
        when (mark) {
            RowMark.BEGUN -> stringResource(R.string.games_mark_begun)
            RowMark.ENDED -> stringResource(R.string.games_mark_ended)
            RowMark.NONE -> null
        }

    // Opaque, not transparent over the window: rows are drawn in list order, so a row
    // sliding down is drawn over the row arriving above it and uncovers it on the way, and
    // the list reads as making room. Left see-through, the two would ghost through each
    // other for the length of the slide. The tints are opaque colours for the same reason.
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            // The colour bar says which server this is to the eye, and the tint what is new
            // about the game; this says both to TalkBack.
            .semantics { stateDescription = listOfNotNull(markText, "on $serverName").joinToString(", ") },
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
            GameRowContent(game, showIcon)
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Hairline() }
    }
}

@Composable
private fun GameRowContent(
    game: GameSummary,
    showIcon: Boolean,
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            PlayerIcon(
                game.opponent.iconUrl,
                game.opponent.handle,
                game.id.server,
                size = ICON_SIZE.dp,
            )
            Spacer(Modifier.width(12.dp))
        }
        // The text and what stands right of it, centred in the row as one block. An outcome
        // sits on the name's baseline whether or not a rating line follows it, so the
        // column of outcomes stays level down the list; a clock, always one line, is
        // centred against the two lines beside it.
        Row(Modifier.weight(1f)) {
            GameRowText(game, Modifier.weight(1f).alignBy(FirstBaseline))
            Spacer(Modifier.width(12.dp))
            // A finished game has no clock to show; what it has instead is how it went.
            if (game.isFinished) {
                OutcomeLabel(game.result, game.myColor, game.ratingChange, Modifier.alignBy(FirstBaseline))
            } else {
                RemainingTimeLabel(
                    game.remainingTime,
                    Modifier.align(Alignment.CenterVertically),
                    muted = !game.isMyTurn,
                )
            }
        }
    }
}

@Composable
private fun GameRowText(
    game: GameSummary,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // One line, always: a name wrapped under a long outcome ("Won by resignation")
        // pushed the line below out of the row. The handle gives way, the rank does not.
        Row {
            Text(
                game.opponent.handle,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            game.opponent.rank?.takeIf { it.isNotBlank() }?.let { rank ->
                Text(
                    " ($rank)",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // The date is the part a glance at the list is for, so when a long outcome on the
        // right squeezes this line it is the board and move count that give way, not it.
        Row {
            val progress =
                when {
                    // A board this app cannot draw: say where it can be seen.
                    game.onlyOnWeb -> stringResource(R.string.games_on_the_web)

                    // A finished game gets nothing here. How long it ran is a detail
                    // the board shows, and OGS's game list does not say it at all, so a
                    // count would set the two servers' rows apart; "finished" would say
                    // what the tab and the outcome beside it already say.
                    game.isFinished -> null

                    // A game being counted is not waiting for a move either, and
                    // saying "move 22" would send the user looking for one.
                    game.pendingAction == PendingAction.SCORE -> stringResource(R.string.games_counting)

                    else -> stringResource(R.string.games_move, game.moveNumber)
                }
            Text(
                listOfNotNull(Formatting.boardSize(game.boardSize, game.handicap, game.boardHeight), progress)
                    .joinToString(", "),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ", ${Formatting.relative(game.lastMoveAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
