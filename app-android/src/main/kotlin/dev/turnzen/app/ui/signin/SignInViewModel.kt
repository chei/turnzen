package dev.turnzen.app.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.SessionManager
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GoServerException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = username.isNotBlank() && password.isNotEmpty() && !loading
}

class SignInViewModel(
    private val session: SessionManager,
    initialError: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState(error = initialError))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    val serverName: String
        get() = session.serverName

    val registrationUrl: String
        get() = session.registrationUrl

    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                session.signIn(Credentials(s.username.trim(), s.password))
            } catch (e: GoServerException) {
                _state.update { it.copy(loading = false, error = messageFor(e)) }
            }
        }
    }

    private fun messageFor(e: GoServerException): String {
        val server = session.serverName
        return when (e) {
            is GoServerException.InvalidCredentials -> "Wrong username or password."
            is GoServerException.Unreachable -> "Couldn't reach $server. Check your connection and try again."
            is GoServerException.RateLimited -> "$server is limiting sign-ins right now. Try again in a few minutes."
            else -> "Sign-in failed: ${e.message}"
        }
    }
}
