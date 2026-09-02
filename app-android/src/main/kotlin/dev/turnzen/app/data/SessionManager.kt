package dev.turnzen.app.data

import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    /** App start: the stored session has not been checked yet. */
    data object Unknown : AuthState

    data class SignedOut(
        val reason: String? = null,
    ) : AuthState

    data class SignedIn(
        val user: SignedInUser,
    ) : AuthState
}

/**
 * Owns the server client and the sign-in state. Every server call from the UI goes
 * through [call], which transparently re-signs in once when the session has expired.
 */
class SessionManager(
    private val credentials: CredentialStore,
    private val servers: ServerSelection,
    private val clientFactory: (serverUrl: String) -> TurnBasedGoServerClient,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var client: TurnBasedGoServerClient = clientFactory(servers.serverUrl)
    private val reloginLock = Mutex()

    val serverName: String
        get() = client.serverName

    val registrationUrl: String
        get() = client.registrationUrl

    val signedInUser: SignedInUser?
        get() = (_state.value as? AuthState.SignedIn)?.user

    /** Called once at app start: restore the stored session, or fall back to a silent sign-in. */
    suspend fun restore() {
        val storedSession = credentials.session
        val storedUser = credentials.user
        if (storedSession == null || credentials.credentials == null) {
            _state.value = AuthState.SignedOut()
            return
        }
        client.restoreSession(storedSession)
        try {
            val user = client.currentUser()
            remember(user)
        } catch (e: GoServerException.SessionExpired) {
            try {
                reSignIn()
            } catch (e: GoServerException) {
                // reSignIn already updated the state for credential problems.
                if (e !is GoServerException.InvalidCredentials && storedUser != null) {
                    _state.value = AuthState.SignedIn(storedUser)
                }
            }
        } catch (e: GoServerException) {
            // Offline or server trouble: keep the last known user; screens report the error.
            if (storedUser !=
                null
            ) {
                _state.value = AuthState.SignedIn(storedUser)
            } else {
                _state.value = AuthState.SignedOut()
            }
        }
    }

    suspend fun signIn(creds: Credentials): SignedInUser {
        val user = client.signIn(creds)
        credentials.credentials = creds
        remember(user)
        return user
    }

    suspend fun signOut() {
        try {
            client.signOut()
        } catch (e: GoServerException) {
            // Local state is cleared regardless; the server session simply ages out.
        } finally {
            credentials.clear()
            _state.value = AuthState.SignedOut()
        }
    }

    /** Switches server. Signs out because a session belongs to one server. */
    suspend fun changeServer(serverUrl: String) {
        signOut()
        servers.selectServer(serverUrl)
        client = clientFactory(serverUrl)
    }

    suspend fun <T> call(block: suspend (TurnBasedGoServerClient) -> T): T =
        try {
            block(client)
        } catch (e: GoServerException.SessionExpired) {
            reSignIn()
            block(client)
        }

    private suspend fun reSignIn() {
        reloginLock.withLock {
            val creds = credentials.credentials
            if (creds == null) {
                _state.value = AuthState.SignedOut()
                throw GoServerException.SessionExpired()
            }
            try {
                val user = client.signIn(creds)
                remember(user)
            } catch (e: GoServerException.InvalidCredentials) {
                credentials.session = null
                _state.value =
                    AuthState.SignedOut("Your ${client.serverName} password seems to have changed. Sign in again.")
                throw e
            }
        }
    }

    private fun remember(user: SignedInUser) {
        credentials.user = user
        credentials.session = client.exportSession()
        _state.value = AuthState.SignedIn(user)
    }
}
