package dev.turnzen.app.ui.signin

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.turnzen.app.R
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.ui.common.ErrorMessage
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.PlayerIcon
import dev.turnzen.app.ui.common.ServerBar
import dev.turnzen.app.ui.common.TextAction
import dev.turnzen.app.ui.common.playerColorKey
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.goserver.SignInMethod
import kotlinx.coroutines.launch

/**
 * One panel per server, in [Accounts] order, each showing that server's own state: signed in
 * as someone (with Sign out), or its way of signing in. Both servers are equals; the panels
 * differ only because the servers do — DGS takes a username and password, OGS signs in on
 * its own page.
 *
 * The same panels are the sign-in screen and the account section of Settings, so adding or
 * removing the second server later looks exactly like signing in the first time.
 */
@Composable
fun AccountPanels(
    accounts: Accounts,
    games: GamesStore,
    browserSignInAvailable: Boolean,
    modifier: Modifier = Modifier,
    keepAwake: KeepAwake = KeepAwake.None,
    /** The user's own icon beside "Signed in as", as the games list shows the opponents'. */
    showPlayerIcons: Boolean = false,
) {
    val states by accounts.states.collectAsStateWithLifecycle(accounts.sessions.map { it.state.value })
    val scope = rememberCoroutineScope()
    Column(modifier) {
        accounts.sessions.forEachIndexed { index, session ->
            if (index > 0) {
                Hairline()
                Spacer(Modifier.height(24.dp))
            }
            val state = states.getOrNull(index) ?: session.state.value
            AccountPanel(
                session = session,
                state = state,
                browserSignInAvailable = browserSignInAvailable,
                keepAwake = keepAwake,
                showPlayerIcon = showPlayerIcons,
                onSignOut = { scope.launch { games.signOut(session) } },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountPanel(
    session: SessionManager,
    state: AuthState,
    browserSignInAvailable: Boolean,
    keepAwake: KeepAwake,
    showPlayerIcon: Boolean,
    onSignOut: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerBar(session.serverId, height = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(session.serverName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        when (state) {
            is AuthState.SignedIn -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (showPlayerIcon) {
                        PlayerIcon(
                            state.user.iconUrl,
                            state.user.handle,
                            // The key the games list would use, so the user looks the same
                            // wherever an opponent of theirs might see them in this app.
                            colorKey = playerColorKey(session.serverId, state.user.handle),
                            size = 36.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        stringResource(R.string.accounts_signed_in_as, state.user.handle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction(stringResource(R.string.settings_sign_out), onSignOut, color = colors.accent)
                }
                session.accountUrl?.let { url ->
                    TextAction(
                        stringResource(R.string.accounts_open_on_web, session.serverName),
                        { uriHandler.openUri(url) },
                        color = colors.accent,
                    )
                }
            }

            else -> {
                // Keyed by the reason: a new reason (the session lapsed again) gets a fresh form.
                val reason = (state as? AuthState.SignedOut)?.reason
                when (session.signInMethod) {
                    SignInMethod.PASSWORD -> {
                        val vm =
                            viewModel(
                                key = "signin-${session.serverId.value}-$reason",
                            ) { SignInViewModel(session, reason) }
                        PasswordForm(vm)
                    }

                    SignInMethod.BROWSER -> {
                        val vm =
                            viewModel(key = "browser-signin-${session.serverId.value}-$reason") {
                                BrowserSignInViewModel(session, browserSignInAvailable, reason, keepAwake)
                            }
                        BrowserForm(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordForm(viewModel: SignInViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    UnderlinedField(
        label = stringResource(R.string.signin_username),
        value = state.username,
        onChange = viewModel::onUsername,
        kind = CredentialKind.USERNAME,
        webDomain = viewModel.passwordDomain,
    )
    Spacer(Modifier.height(16.dp))
    UnderlinedField(
        label = stringResource(R.string.signin_password),
        value = state.password,
        onChange = viewModel::onPassword,
        kind = CredentialKind.PASSWORD,
        webDomain = viewModel.passwordDomain,
        onDone = viewModel::submit,
    )
    Spacer(Modifier.height(20.dp))
    state.error?.let {
        ErrorMessage(it, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
    }
    PillButton(
        text =
            if (state.loading) {
                stringResource(
                    R.string.signin_signing_in,
                )
            } else {
                stringResource(R.string.signin_action_to, viewModel.serverName)
            },
        onClick = viewModel::submit,
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    TextAction(
        stringResource(R.string.signin_register_on, viewModel.serverName),
        { uriHandler.openUri(viewModel.registrationUrl) },
        color = colors.accent,
    )
}

@Composable
private fun BrowserForm(viewModel: BrowserSignInViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    // The page is opened from here, where there is an activity; the view model only asks.
    //
    // In a Custom Tab rather than the browser itself: the tab runs in Turnzen's own task, so
    // when the redirect at the end of the sign-in brings the app forward, MainActivity (singleTask)
    // clears it away, and Back closes it too. Opened as an ordinary browser tab, the finished
    // sign-in page stayed behind among the user's tabs. A browser without Custom Tabs simply
    // opens the page as before.
    LaunchedEffect(state.page) {
        state.page?.let {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it))
            viewModel.onPageOpened()
        }
    }
    Text(
        stringResource(
            when {
                !viewModel.available -> R.string.signin_browser_unavailable
                state.waiting -> R.string.signin_browser_waiting
                else -> R.string.signin_browser_explain
            },
            viewModel.serverName,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.ink2,
    )
    Spacer(Modifier.height(12.dp))
    state.error?.let {
        ErrorMessage(it, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
    }
    if (state.waiting) {
        PillButton(
            text = stringResource(R.string.board_cancel),
            onClick = viewModel::cancel,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        PillButton(
            text = stringResource(R.string.signin_action_with, viewModel.serverName),
            onClick = viewModel::signIn,
            enabled = viewModel.available,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(4.dp))
    TextAction(
        stringResource(R.string.signin_register_on, viewModel.serverName),
        { uriHandler.openUri(viewModel.registrationUrl) },
        color = colors.accent,
    )
}

@Composable
private fun UnderlinedField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    kind: CredentialKind,
    webDomain: String?,
    onDone: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        CredentialField(value, onChange, kind, webDomain, onDone)
    }
}
