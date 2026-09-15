package dev.turnzen.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.turnzen.app.AppContainer
import dev.turnzen.app.R
import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.ThemeMode
import dev.turnzen.app.ui.board.BoardScreen
import dev.turnzen.app.ui.board.BoardViewModel
import dev.turnzen.app.ui.common.MainTab
import dev.turnzen.app.ui.common.TurnzenBottomBar
import dev.turnzen.app.ui.games.GamesScreen
import dev.turnzen.app.ui.games.GamesTab
import dev.turnzen.app.ui.games.GamesViewModel
import dev.turnzen.app.ui.settings.SettingsScreen
import dev.turnzen.app.ui.settings.SettingsViewModel
import dev.turnzen.app.ui.signin.SignInScreen
import dev.turnzen.app.ui.signin.SignInViewModel
import dev.turnzen.app.ui.theme.TurnzenTheme
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.goserver.GameId

/**
 * Root composable: decides between sign-in and the signed-in shell, and hosts the
 * hand-rolled navigation (two tabs plus an optional open game). Navigation-compose was
 * left out on purpose: three destinations do not justify a routing library yet.
 */
@Composable
fun TurnzenApp(container: AppContainer) {
    val auth by container.session.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (container.session.state.value is AuthState.Unknown) container.session.restore()
    }

    val darkTheme =
        when (settings.theme) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }

    TurnzenTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            when (val a = auth) {
                AuthState.Unknown -> {
                    Box(Modifier.fillMaxSize().background(colors.surface))
                }

                is AuthState.SignedOut -> {
                    val vm = viewModel(key = "signin-${a.reason}") { SignInViewModel(container.session, a.reason) }
                    SignInScreen(vm)
                }

                is AuthState.SignedIn -> {
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
    val openGame = openGameId?.let(::GameId)
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    fun advanceFrom(id: GameId) {
        openGameId = container.games.nextAfter(id, all = gamesTab == GamesTab.ALL)?.value
    }

    BackHandler(enabled = openGame != null) { openGameId = null }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val game = openGame
            when {
                game != null && tab == MainTab.GAMES -> {
                    val vm =
                        viewModel(key = "board-${game.value}") {
                            BoardViewModel(
                                gameId = game,
                                session = container.session,
                                games = container.games,
                                ladders = container.ladders,
                                // The queue's next game, warmed while this one is on screen.
                                nextGame = { container.games.nextAfter(game, all = gamesTab == GamesTab.ALL) },
                                onMoveSent = { sent -> advanceFrom(sent) },
                                // A resigned game is over: back to the list, not on to the
                                // next game in the queue.
                                onGameEnded = { openGameId = null },
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
                    val vm = viewModel { GamesViewModel(container.session, container.games) }
                    GamesScreen(
                        vm,
                        tab = gamesTab,
                        onTabChange = { gamesTab = it },
                        onOpenGame = { openGameId = it.value },
                    )
                }

                else -> {
                    val vm = viewModel { SettingsViewModel(container.session, container.settings, container.games) }
                    SettingsScreen(vm)
                }
            }
        }
        TurnzenBottomBar(
            selected = tab,
            onSelect = { selected ->
                if (selected == MainTab.GAMES && tab == MainTab.GAMES) openGameId = null
                tab = selected
            },
            gamesLabel = stringResource(R.string.nav_games),
            settingsLabel = stringResource(R.string.nav_settings),
        )
    }
}
