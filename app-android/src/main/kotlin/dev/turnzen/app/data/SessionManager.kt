package dev.turnzen.app.data

import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.SignInMethod
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
 * Owns one server's client and its sign-in state; the app has one per server (see
 * [Accounts]). Every server call from the UI goes through [call], which transparently
 * re-signs in once when the session has expired.
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

    // Bumped on every successful (re-)sign-in. When a burst of calls all fail against the
    // same dead session (the Games screen fires two at once), only the first should sign in;
    // the rest reuse its fresh session. A second, redundant sign-in would race on the shared
    // client's cookies and could invalidate the very session the first retry is about to use
    // — which surfaced as a spurious "signed out" message even though re-login had worked.
    private var sessionGeneration = 0

    /** Which server this session belongs to; every game it lists carries it. */
    val serverId: ServerId
        get() = client.serverId

    val serverName: String
        get() = client.serverName

    val signInMethod: SignInMethod
        get() = client.signInMethod

    val isSignedIn: Boolean
        get() = _state.value is AuthState.SignedIn

    val registrationUrl: String
        get() = client.registrationUrl

    val passwordDomain: String?
        get() = client.passwordDomain

    /** The current server's web page for a game, for the "open on the web" escape hatch. */
    fun gameUrl(id: GameId): String? = client.gameUrl(id)

    /** The signed-in user's page on the server's website, if the server has one. */
    val accountUrl: String?
        get() = signedInUser?.let(client::accountUrl)

    val signedInUser: SignedInUser?
        get() = (_state.value as? AuthState.SignedIn)?.user

    /** Called once at app start: restore the stored session, or fall back to a silent sign-in. */
    suspend fun restore() {
        val storedSession = credentials.session
        val storedUser = credentials.user
        // A password server needs the password too, to sign in again when the session lapses;
        // a browser sign-in never had one, and its session is all there is.
        val needsPassword = client.signInMethod == SignInMethod.PASSWORD
        if (storedSession == null || (needsPassword && credentials.credentials == null)) {
            _state.value = AuthState.SignedOut()
            return
        }
        client.restoreSession(storedSession)
        try {
            val user = client.currentUser()
            remember(user)
        } catch (e: GoServerException.SessionExpired) {
            try {
                reSignIn(sessionGeneration)
            } catch (e: GoServerException) {
                // reSignIn already updated the state for credential problems, and for a
                // browser sign-in that has lapsed (SessionExpired).
                if (e !is GoServerException.InvalidCredentials &&
                    e !is GoServerException.SessionExpired &&
                    storedUser != null
                ) {
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

    /**
     * Signs in on the server's own web page ([SignInMethod.BROWSER]). [openPage] is asked to
     * show the page; the call returns once the server has handed the sign-in back.
     */
    suspend fun signInInBrowser(openPage: (url: String) -> Unit): SignedInUser {
        val user = client.signInInBrowser(openPage)
        credentials.credentials = null
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

    suspend fun <T> call(block: suspend (TurnBasedGoServerClient) -> T): T {
        val generationBefore = sessionGeneration
        val result =
            try {
                block(client)
            } catch (e: GoServerException.SessionExpired) {
                reSignIn(generationBefore)
                block(client)
            }
        persistSession()
        return result
    }

    /**
     * Keeps the stored session in step with the client's. A server that renews its tokens as
     * they age (OGS) hands out a new pair during ordinary calls, and the old one stops working:
     * a session saved only at sign-in would be dead by the next launch.
     */
    private fun persistSession() {
        if (_state.value !is AuthState.SignedIn) return
        val current = client.exportSession() ?: return
        if (current != credentials.session) credentials.session = current
    }

    private suspend fun reSignIn(seenGeneration: Int) {
        reloginLock.withLock {
            // Another call already re-signed-in while we waited for the lock: reuse its
            // session instead of signing in a second time.
            if (sessionGeneration != seenGeneration) return
            val creds = credentials.credentials
            if (creds == null) {
                // Nothing to sign in with silently: a browser sign-in has to be done again by
                // the user. The session is dropped so the next launch does not retry it.
                credentials.session = null
                _state.value = AuthState.SignedOut("${client.serverName} signed you out. Sign in again.")
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
        sessionGeneration++
        _state.value = AuthState.SignedIn(user)
    }
}
