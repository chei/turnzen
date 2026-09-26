package dev.turnzen.app.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.data.SessionManager
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps the app from being frozen while the browser is in front (see
 * `SignInKeepAwakeService`). An interface so the view model stays testable on the JVM.
 */
interface KeepAwake {
    fun start()

    fun stop()

    object None : KeepAwake {
        override fun start() = Unit

        override fun stop() = Unit
    }
}

data class BrowserSignInUiState(
    /** The sign-in page is open in the browser and the app is waiting for its answer. */
    val waiting: Boolean = false,
    /** A page the screen still has to open; cleared once it has. */
    val page: String? = null,
    val error: String? = null,
)

/**
 * The browser sign-in of one server (OGS): the user signs in on the server's own page, so
 * Turnzen never handles the password. The screen opens [BrowserSignInUiState.page] and
 * reports back with [onPageOpened]; the rest is waiting.
 */
class BrowserSignInViewModel(
    private val session: SessionManager,
    /** False in a build without the server's registered client id: the button explains itself instead. */
    val available: Boolean = true,
    initialError: String? = null,
    private val keepAwake: KeepAwake = KeepAwake.None,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowserSignInUiState(error = initialError))
    val state: StateFlow<BrowserSignInUiState> = _state.asStateFlow()
    private var job: Job? = null

    val serverId: ServerId
        get() = session.serverId

    val serverName: String
        get() = session.serverName

    val registrationUrl: String
        get() = session.registrationUrl

    fun signIn() {
        if (!available || _state.value.waiting) return
        _state.update { it.copy(waiting = true, error = null) }
        keepAwake.start()
        job =
            viewModelScope.launch {
                try {
                    // Called off the main thread once the redirect is being listened for.
                    session.signInInBrowser { url -> _state.update { it.copy(page = url) } }
                    _state.update { it.copy(waiting = false, page = null) }
                } catch (e: GoServerException) {
                    _state.update { it.copy(waiting = false, page = null, error = messageFor(e)) }
                } finally {
                    keepAwake.stop()
                }
            }
    }

    fun onPageOpened() = _state.update { it.copy(page = null) }

    /** The user gave up on the browser: stop waiting for it. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(waiting = false, page = null) }
    }

    private fun messageFor(e: GoServerException): String {
        val server = session.serverName
        return when (e) {
            is GoServerException.InvalidCredentials -> "$server didn't sign you in. Try again."
            is GoServerException.Unreachable -> "Couldn't reach $server. Check your connection and try again."
            is GoServerException.RateLimited -> "$server is limiting sign-ins right now. Try again in a few minutes."
            is GoServerException.Protocol -> "$server answered in a way Turnzen didn't understand. ${e.message}"
            else -> "Sign-in failed: ${e.message}"
        }
    }
}
