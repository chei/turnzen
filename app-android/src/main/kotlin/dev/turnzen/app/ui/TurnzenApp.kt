package dev.turnzen.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.turnzen.app.AppContainer
import dev.turnzen.app.R
import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.ThemeMode
import dev.turnzen.app.notify.OpenRequest
import dev.turnzen.app.ui.board.BoardScreen
import dev.turnzen.app.ui.board.BoardViewModel
import dev.turnzen.app.ui.common.MainTab
import dev.turnzen.app.ui.common.OutboxLine
import dev.turnzen.app.ui.common.TurnzenBottomBar
import dev.turnzen.app.ui.common.TurnzenNavRail
import dev.turnzen.app.ui.games.GamesScreen
import dev.turnzen.app.ui.games.GamesTab
import dev.turnzen.app.ui.games.GamesViewModel
import dev.turnzen.app.ui.settings.SettingsScreen
import dev.turnzen.app.ui.settings.SettingsViewModel
import dev.turnzen.app.ui.signin.SignInScreen
import dev.turnzen.app.ui.theme.TurnzenTheme
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.ServerId

/**
 * Root composable: decides between sign-in and the signed-in shell, and hosts the
 * hand-rolled navigation (two tabs plus an optional open game). Navigation-compose was
 * left out on purpose: three destinations do not justify a routing library yet.
 */
@Composable
fun TurnzenApp(container: AppContainer) {
    val accounts = container.accounts
    val auth by accounts.states.collectAsStateWithLifecycle(accounts.sessions.map { it.state.value })
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { accounts.restore() }

    val anySignedIn = auth.any { it is AuthState.SignedIn }
    // The sign-in screen stays up after the first sign-in until the user continues, so they
    // can sign in to the second server too. Signing out of every server brings it back; one
    // server signing the user out while the other stays does not (the games list says so).
    var onSignIn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(anySignedIn, auth.none { it is AuthState.Unknown }) {
        if (!anySignedIn && auth.none { it is AuthState.Unknown }) onSignIn = true
    }

    val darkTheme =
        when (settings.theme) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }

    TurnzenTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            when {
                // Not yet known whether a stored session still works: nothing to decide on.
                auth.any { it is AuthState.Unknown } -> {
                    Box(Modifier.fillMaxSize().background(colors.surface))
                }

                !anySignedIn || onSignIn -> {
                    // A tapped notification has nowhere to go until the user is past sign-in,
                    // and should not jump anywhere later on its own.
                    LaunchedEffect(Unit) { container.openRequests.value = null }
                    SignInScreen(
                        accounts = accounts,
                        games = container.games,
                        browserSignInAvailable = container.ogsAvailable,
                        keepAwake = container.keepAwake,
                        canContinue = anySignedIn,
                        onContinue = { onSignIn = false },
                    )
                }

                else -> {
                    SignedInShell(container)
                }
            }
        }
    }
}

/**
 * Identifies this process. A new value every time the app's process starts, because a fresh
 * process re-runs this initialiser.
 */
private val processId = System.nanoTime()

/**
 * Saves the open game together with [processId], and restores it only within the same
 * process.
 *
 * Saved instance state outlives the process: Android hands it back both after a
 * configuration change (same process, the activity recreated under it) and after it has
 * killed a backgrounded app entirely. Those want opposite answers here. Rotating should keep
 * the game open; launching again tomorrow should not, because everything the board needs —
 * the games list and the record cache — lives in memory and died with the process, so the
 * restored board would come up with no header, no grid and a failed fetch, on a screen that
 * has no way back to the list. A cold start belongs on the games list.
 */
private val openGameSaver =
    listSaver<MutableState<String?>, Any>(
        save = { state -> state.value?.let { listOf(processId, it) }.orEmpty() },
        restore = { saved -> mutableStateOf(saved.takeIf { it.firstOrNull() == processId }?.get(1) as String?) },
    )

/** The open game as a Bundle can hold it: "server:id". */
private fun encodeGameId(id: GameId): String = "${id.server.value}:${id.value}"

private fun decodeGameId(saved: String): GameId =
    GameId(ServerId(saved.substringBefore(':')), saved.substringAfter(':'))

/**
 * The shell's navigation, as the board view models reach it. A board view model outlives the
 * activity (rotation, a theme or font change recreate it), so the callbacks it was built with
 * must not close over one composition's state: after a recreation they would write to a
 * state nothing reads any more, and Send would leave the user on the game they just played.
 * This holder lives in the same ViewModelStore as the boards, and every composition points it
 * at its own state.
 */
private class ShellNavigation : ViewModel() {
    var nextInQueue: (GameId) -> GameId? = { null }
    var advanceFrom: (GameId) -> Unit = {}
    var close: () -> Unit = {}
}

@Composable
private fun SignedInShell(container: AppContainer) {
    var tab by rememberSaveable { mutableStateOf(MainTab.GAMES) }
    // Which games tab is active. Lifted above the open-game boundary so it survives opening
    // and closing a game, and so Skip and auto-advance know which list to move through.
    var gamesTab by rememberSaveable { mutableStateOf(GamesTab.YOUR_TURN) }
    // Saved rather than merely remembered: any configuration change (rotation, theme, font
    // size, a multi-window resize) recreates the activity, and a plain remember would drop
    // the open game and bounce the user back to the list. Held as the raw id because that is
    // what a Bundle can carry — and tagged with the process that opened it, see
    // [openGameSaver].
    var openGameId by rememberSaveable(saver = openGameSaver) { mutableStateOf<String?>(null) }
    val openGame = openGameId?.let(::decodeGameId)
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // The archive is a list to look things up in, not a queue to work through, so nothing
    // steps on from a finished game: it closes back to the list it was opened from.
    fun nextInQueue(id: GameId): GameId? =
        if (gamesTab == GamesTab.FINISHED) null else container.games.nextAfter(id, all = gamesTab == GamesTab.ALL)

    fun advanceFrom(id: GameId) {
        openGameId = nextInQueue(id)?.let(::encodeGameId)
    }

    val navigation = viewModel { ShellNavigation() }
    SideEffect {
        navigation.nextInQueue = ::nextInQueue
        navigation.advanceFrom = ::advanceFrom
        navigation.close = { openGameId = null }
    }

    BackHandler(enabled = openGame != null) { openGameId = null }

    // A tapped notification: the game it names, the "Your turn" list, or the accounts.
    val openRequest by container.openRequests.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        when (val request = openRequest ?: return@LaunchedEffect) {
            is OpenRequest.Game -> {
                tab = MainTab.GAMES
                gamesTab = GamesTab.YOUR_TURN
                openGameId = encodeGameId(request.id)
            }

            OpenRequest.YourTurn -> {
                tab = MainTab.GAMES
                gamesTab = GamesTab.YOUR_TURN
                openGameId = null
            }

            OpenRequest.Settings -> {
                tab = MainTab.SETTINGS
                openGameId = null
            }
        }
        container.openRequests.value = null
    }

    // A window wider than it is tall — a phone turned sideways — takes the tabs to the side,
    // so the short edge goes to the board instead of to the bottom bar.
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp > configuration.screenHeightDp
    // ...and the status bar goes too: in landscape its strip is height the board could use,
    // and a correspondence game does not need the clock in view. A swipe from the top edge
    // brings it back for a moment. The insets it leaves are zero, so the screens' status-bar
    // padding collapses with it.
    //
    // On an open game the navigation bar goes as well, so the board has the whole height.
    // A swipe in from the edge brings it back for a moment, as with the status bar, so a
    // stray swipe off the board does not leave the app.
    HideSystemBars(statusBar = wide, navigationBar = wide && openGame != null && tab == MainTab.GAMES)
    val gamesLabel = stringResource(R.string.nav_games)
    val settingsLabel = stringResource(R.string.nav_settings)

    fun selectTab(selected: MainTab) {
        if (selected == MainTab.GAMES && tab == MainTab.GAMES) openGameId = null
        tab = selected
    }

    // The screens below take turns in one slot, so each leaves the composition while another
    // is shown — and what they hold with rememberSaveable (the games list's scroll position
    // per tab, above all) goes with them. Saveable state only outlives that when something
    // keeps it, which is what a navigation library would do; this holder does it here, per
    // screen, so the list comes back from a game where it was left.
    val screenStates = rememberSaveableStateHolder()

    val content: @Composable () -> Unit = {
        val game = openGame
        when {
            game != null && tab == MainTab.GAMES -> {
                val vm =
                    viewModel(key = "board-${encodeGameId(game)}") {
                        BoardViewModel(
                            gameId = game,
                            session = container.accounts.session(game.server),
                            games = container.games,
                            outbox = container.outbox,
                            ladders = container.ladders,
                            // The queue's next game, warmed while this one is on screen.
                            nextGame = { navigation.nextInQueue(game) },
                            onMoveSent = { sent -> navigation.advanceFrom(sent) },
                            // A resigned game is over: back to the list, not on to the
                            // next game in the queue.
                            onGameEnded = { navigation.close() },
                        )
                    }
                // Reload whenever the game is (re)opened: the cached ViewModel may hold
                // state from before a move was sent, so its board could be stale.
                LaunchedEffect(game) { vm.load() }
                BoardScreen(
                    vm,
                    settings.board,
                    onSkip = { advanceFrom(game) },
                )
            }

            tab == MainTab.GAMES -> {
                val vm = viewModel { GamesViewModel(container.accounts, container.games) }
                screenStates.SaveableStateProvider("games") {
                    GamesScreen(
                        vm,
                        tab = gamesTab,
                        onTabChange = { gamesTab = it },
                        onOpenGame = { openGameId = encodeGameId(it) },
                        onSignInAgain = { tab = MainTab.SETTINGS },
                        showPlayerIcons = settings.playerIcons,
                    )
                }
            }

            else -> {
                val vm = viewModel { SettingsViewModel(container.settings) { container.notifier.allowed } }
                screenStates.SaveableStateProvider("settings") {
                    SettingsScreen(vm, container.accounts, container.games, container.ogsAvailable, container.keepAwake)
                }
            }
        }
    }

    val outboxLine: @Composable () -> Unit = {
        OutboxLine(
            outbox = container.outbox,
            onOpen = { id ->
                tab = MainTab.GAMES
                openGameId = encodeGameId(id)
            },
        )
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            TurnzenNavRail(tab, ::selectTab, gamesLabel, settingsLabel)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The rail takes the start edge's insets; this side has the rest.
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.End + WindowInsetsSides.Bottom),
                    ),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                outboxLine()
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            outboxLine()
            // An open game is a detail of the Games tab, left with the back gesture, so the
            // tabs give their 80dp to the board screen, which never has height to spare. The
            // system navigation bar stays, and the content keeps clear of it instead.
            if (openGame != null && tab == MainTab.GAMES) {
                Box(Modifier.navigationBarsPadding())
            } else {
                TurnzenBottomBar(tab, ::selectTab, gamesLabel, settingsLabel)
            }
        }
    }
}

@Composable
private fun HideSystemBars(
    statusBar: Boolean,
    navigationBar: Boolean,
) {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, statusBar, navigationBar) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        fun showOrHide(
            type: Int,
            hidden: Boolean,
        ) = if (hidden) controller.hide(type) else controller.show(type)

        showOrHide(WindowInsetsCompat.Type.statusBars(), statusBar)
        showOrHide(WindowInsetsCompat.Type.navigationBars(), navigationBar)
        // Signing out leaves the shell: the sign-in screen gets its bars back.
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
