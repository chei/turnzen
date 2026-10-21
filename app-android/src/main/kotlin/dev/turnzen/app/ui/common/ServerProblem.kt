package dev.turnzen.app.ui.common

import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId

/**
 * A failed server call, in the words the screen shows.
 *
 * [transient] separates the failures the user waits out — the server cannot be reached, or
 * has asked us to slow down — from the ones that need them to do something. A transient
 * problem is a state, not an error: it is shown quietly and always with a way to retry,
 * never in the urgent red that means "this needs you". Nothing is wrong with a
 * correspondence game whose app spent an hour unable to reach the server.
 */
data class ServerProblem(
    /** One short line, for a screen with nothing else to show ("Can't reach DGS"). */
    val title: String,
    /** What happened and what to do about it. */
    val message: String,
    val transient: Boolean,
    /** The server it is about, where it is about one; the screen marks the line with its colour. */
    val server: ServerId? = null,
)

/**
 * The copy for a server failure. [serverName] is the server's own name, so the message
 * blames the server the user actually chose rather than "the server".
 *
 * Connectivity is never probed, so a server that never answered may be down or the phone
 * offline, and the copy says only that we could not reach it. A server that answered with
 * an error (HTTP 5xx) was reached, so that copy says it is down rather than blaming the
 * connection.
 */
fun problemFor(
    e: GoServerException,
    serverName: String,
): ServerProblem =
    when (e) {
        is GoServerException.Unreachable -> {
            ServerProblem(
                title = "Can't reach $serverName",
                message = "Check your connection and try again.",
                transient = true,
            )
        }

        is GoServerException.Unavailable -> {
            ServerProblem(
                title = "$serverName is down",
                message = "$serverName isn't taking requests right now. Try again later.",
                transient = true,
            )
        }

        is GoServerException.RateLimited -> {
            ServerProblem(
                title = "$serverName is busy",
                message = "$serverName asked us to slow down. Try again in a few minutes.",
                transient = true,
            )
        }

        is GoServerException.SessionExpired, is GoServerException.InvalidCredentials -> {
            ServerProblem(
                title = "Signed out",
                message = "Signed out by $serverName. Sign in again.",
                transient = false,
            )
        }

        is GoServerException.MoveRejected -> {
            ServerProblem(
                title = "Move rejected",
                message = "$serverName rejected the move (${e.code}).",
                transient = false,
            )
        }

        else -> {
            ServerProblem(
                title = "Something went wrong",
                message = e.message ?: "Something went wrong.",
                transient = false,
            )
        }
    }
