package dev.turnzen.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.CheckInterval
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.ThemeMode
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.signin.AccountPanels
import dev.turnzen.app.ui.signin.KeepAwake
import dev.turnzen.app.ui.theme.colors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    accounts: Accounts,
    games: GamesStore,
    browserSignInAvailable: Boolean,
    keepAwake: KeepAwake,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var choosingTheme by remember { mutableStateOf(false) }
    var choosingInterval by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Whether Android lets the notifications through; read again whenever the screen comes
    // back, since the user may have just changed it in Android's settings.
    var allowed by remember { mutableStateOf(viewModel.notificationsAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = viewModel.notificationsAllowed() }
    val askPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = viewModel.notificationsAllowed()
        }

    // Switching a notification on is when Android's permission is asked for, never before.
    fun switchOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        )

        // The accounts are the sign-in screen's own panels: adding the second server, or
        // signing one out, looks exactly like signing in did.
        AccountPanels(
            accounts,
            games,
            browserSignInAvailable,
            Modifier.padding(horizontal = 20.dp),
            keepAwake,
            showPlayerIcons = settings.playerIcons,
        )
        Spacer(Modifier.height(4.dp))

        Group(stringResource(R.string.settings_group_board)) {
            ToggleRow(stringResource(R.string.settings_coordinates), settings.board.showCoordinates) { on ->
                viewModel.updateBoard { it.copy(showCoordinates = on) }
            }
            ToggleRow(stringResource(R.string.settings_mark_last_move), settings.board.markLastMove) { on ->
                viewModel.updateBoard { it.copy(markLastMove = on) }
            }
            ToggleRow(stringResource(R.string.settings_number_trial_stones), settings.board.numberTrialStones) { on ->
                viewModel.updateBoard { it.copy(numberTrialStones = on) }
            }
        }

        Group(stringResource(R.string.settings_group_notifications)) {
            val notifications = settings.notifications
            ToggleRow(stringResource(R.string.settings_notify_your_move), notifications.yourMove) { on ->
                viewModel.updateNotifications { it.copy(yourMove = on) }
                if (on) switchOn()
            }
            ToggleRow(stringResource(R.string.settings_notify_low_time), notifications.lowTime) { on ->
                viewModel.updateNotifications { it.copy(lowTime = on) }
                if (on) switchOn()
            }
            ToggleRow(stringResource(R.string.settings_notify_messages), notifications.messages) { on ->
                viewModel.updateNotifications { it.copy(messages = on) }
                if (on) switchOn()
            }
            if (notifications.anyOn) {
                ValueRow(stringResource(R.string.settings_check_every), intervalLabel(notifications.checkEvery)) {
                    choosingInterval = true
                }
                if (!allowed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_notifications_blocked),
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.urgent,
                    )
                    ActionRow(stringResource(R.string.settings_notifications_allow)) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
            }
        }

        Group(stringResource(R.string.settings_group_appearance)) {
            ValueRow(stringResource(R.string.settings_theme), themeLabel(settings.theme)) { choosingTheme = true }
            ToggleRow(stringResource(R.string.settings_player_icons), settings.playerIcons, viewModel::setPlayerIcons)
        }

        Group(stringResource(R.string.settings_group_about)) {
            ActionRow(stringResource(R.string.settings_source)) { uriHandler.openUri(viewModel.sourceUrl) }
            ActionRow(stringResource(R.string.settings_report)) { uriHandler.openUri(viewModel.issuesUrl) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (choosingTheme) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            label = { themeLabel(it) },
            current = settings.theme,
            onPick = {
                viewModel.setTheme(it)
                choosingTheme = false
            },
            onDismiss = { choosingTheme = false },
        )
    }

    if (choosingInterval) {
        ChoiceDialog(
            title = stringResource(R.string.settings_check_every),
            options = CheckInterval.entries,
            label = { intervalLabel(it) },
            current = settings.notifications.checkEvery,
            onPick = {
                viewModel.setCheckEvery(it)
                choosingInterval = false
            },
            onDismiss = { choosingInterval = false },
        )
    }
}

@Composable
private fun intervalLabel(interval: CheckInterval): String =
    stringResource(
        when (interval) {
            CheckInterval.MIN_15 -> R.string.interval_15_min
            CheckInterval.MIN_30 -> R.string.interval_30_min
            CheckInterval.HOUR_1 -> R.string.interval_1_h
            CheckInterval.HOUR_3 -> R.string.interval_3_h
        },
    )

@Composable
private fun Group(
    title: String,
    content: @Composable () -> Unit,
) {
    // Full width, each row padding its own text: a row's tap highlight runs edge to edge
    // rather than stopping flush against the label and the value.
    Column {
        Text(
            title,
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.ink2,
        )
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SettingsRow(
    label: String,
    labelColor: Color = colors.ink,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        Box(Modifier.padding(horizontal = 20.dp)) { Hairline() }
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(label, onClick = onClick) {
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String =
    stringResource(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        },
    )

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    label: @Composable (T) -> String,
    current: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                options.forEach { mode ->
                    Text(
                        label(mode),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(mode) }
                                .padding(vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (mode == current) colors.accent else colors.ink,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.board_cancel), color = colors.ink) }
        },
    )
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
) {
    SettingsRow(label, labelColor = colors.accent, onClick = onClick)
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(label, onClick = { onChange(!checked) }) {
        Text(
            stringResource(if (checked) R.string.settings_on else R.string.settings_off),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink2,
        )
    }
    Box {}
}
