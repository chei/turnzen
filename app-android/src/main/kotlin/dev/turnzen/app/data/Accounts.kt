package dev.turnzen.app.data

import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The servers the app can sign in to, one [SessionManager] each, in the order every screen
 * lists them (OGS first: its sign-in is a single button, so it stays visible above the DGS
 * form when the keyboard is up).
 *
 * At most one account per server. Games carry their server in their [dev.turnzen.goserver.GameId],
 * and [session] finds the session that serves one.
 */
class Accounts(
    val sessions: List<SessionManager>,
) {
    init {
        require(sessions.map { it.serverId }.toSet().size == sessions.size) { "One session per server" }
    }

    fun session(server: ServerId): SessionManager =
        sessions.firstOrNull { it.serverId == server }
            ?: throw IllegalArgumentException("No session for server ${server.value}")

    /** The sessions that are signed in right now. */
    val signedIn: List<SessionManager>
        get() = sessions.filter { it.isSignedIn }

    /** Every session's state, in [sessions] order. */
    val states: Flow<List<AuthState>> = combine(sessions.map { it.state }) { it.toList() }

    private val restoring = Mutex()

    /**
     * Restores every stored session, all at once. The app and a background check can both
     * ask at startup; the second waits for the first and then finds nothing left to do.
     */
    suspend fun restore() =
        restoring.withLock {
            coroutineScope {
                sessions.forEach { session ->
                    if (session.state.value is AuthState.Unknown) launch { session.restore() }
                }
            }
        }
}
