package dev.turnzen.app.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.turnzen.app.R
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor

/**
 * The first screen: one panel per server, and Continue once at least one is signed in. The
 * user may sign in to both before continuing, or to one and add the other later from
 * Settings, which shows the same panels.
 */
@Composable
fun SignInScreen(
    accounts: Accounts,
    games: GamesStore,
    browserSignInAvailable: Boolean,
    keepAwake: KeepAwake,
    canContinue: Boolean,
    onContinue: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            StoneIcon(StoneColor.BLACK, 28.dp)
            StoneIcon(StoneColor.WHITE, 28.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.signin_note_servers),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(36.dp))
        AccountPanels(accounts, games, browserSignInAvailable, keepAwake = keepAwake)
        Spacer(Modifier.height(8.dp))
        PillButton(
            text = stringResource(R.string.signin_continue),
            onClick = onContinue,
            primary = true,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}
