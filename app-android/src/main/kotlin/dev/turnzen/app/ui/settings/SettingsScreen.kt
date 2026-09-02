package dev.turnzen.app.ui.settings

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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.Hairline
import dev.turnzen.app.ui.theme.Tokens

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var editingServer by remember { mutableStateOf(false) }

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

        Group(stringResource(R.string.settings_group_account)) {
            ValueRow(viewModel.serverName, viewModel.userHandle)
            ValueRow(
                stringResource(R.string.settings_server),
                settings.serverUrl.removePrefix("https://").removeSuffix("/"),
            ) {
                editingServer = true
            }
            ActionRow(stringResource(R.string.settings_sign_out), viewModel::signOut)
        }

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

        Group(stringResource(R.string.settings_group_about)) {
            ValueRow(stringResource(R.string.settings_version), viewModel.version)
            ValueRow(stringResource(R.string.settings_license), "MIT")
            ActionRow(stringResource(R.string.settings_source)) { uriHandler.openUri(viewModel.sourceUrl) }
            ActionRow(stringResource(R.string.settings_report)) { uriHandler.openUri(viewModel.issuesUrl) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editingServer) {
        var url by remember { mutableStateOf(settings.serverUrl) }
        AlertDialog(
            onDismissRequest = { editingServer = false },
            containerColor = Tokens.surface,
            title = {
                Text(
                    stringResource(R.string.settings_server_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_server_dialog_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.ink2,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingServer = false
                    viewModel.changeServer(url)
                }) { Text(stringResource(R.string.settings_save), color = Tokens.accent) }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingServer = false },
                ) { Text(stringResource(R.string.board_cancel), color = Tokens.ink) }
            },
        )
    }
}

@Composable
private fun Group(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Tokens.ink2)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SettingsRow(
    label: String,
    labelColor: Color = Tokens.ink,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .height(48.dp),
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
        Hairline()
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(label, onClick = onClick) {
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Tokens.ink2)
    }
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
) {
    SettingsRow(label, labelColor = Tokens.accent, onClick = onClick)
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
            color = Tokens.ink2,
        )
    }
    Box {}
}
