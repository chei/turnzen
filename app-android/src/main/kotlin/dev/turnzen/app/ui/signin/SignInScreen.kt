package dev.turnzen.app.ui.signin

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.ErrorMessage
import dev.turnzen.app.ui.common.PillButton
import dev.turnzen.app.ui.common.StoneIcon
import dev.turnzen.app.ui.theme.Tokens
import dev.turnzen.game.StoneColor

@Composable
fun SignInScreen(viewModel: SignInViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            StoneIcon(StoneColor.BLACK, 32.dp)
            StoneIcon(StoneColor.WHITE, 32.dp)
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = Tokens.ink2)
        Spacer(Modifier.height(44.dp))

        UnderlinedField(
            label = stringResource(R.string.signin_username),
            value = state.username,
            onChange = viewModel::onUsername,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
        )
        Spacer(Modifier.height(20.dp))
        UnderlinedField(
            label = stringResource(R.string.signin_password),
            value = state.password,
            onChange = viewModel::onPassword,
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            onDone = viewModel::submit,
        )
        Spacer(Modifier.height(28.dp))
        state.error?.let {
            ErrorMessage(it, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        PillButton(
            text = if (state.loading) "Signing in…" else stringResource(R.string.signin_action),
            onClick = viewModel::submit,
            primary = true,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.signin_note_1) + "\n" + stringResource(R.string.signin_note_2),
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.signin_no_account),
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.accent,
            modifier = Modifier.clickable { uriHandler.openUri(viewModel.registrationUrl) }.padding(8.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun UnderlinedField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tokens.ink2)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                ),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Tokens.accent,
                    unfocusedIndicatorColor = Tokens.line,
                    cursorColor = Tokens.accent,
                    focusedTextColor = Tokens.ink,
                    unfocusedTextColor = Tokens.ink,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
