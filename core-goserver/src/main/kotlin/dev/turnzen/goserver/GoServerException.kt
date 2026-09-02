package dev.turnzen.goserver

/** Failures a server client reports. The UI maps these to copy; it never sees raw responses. */
sealed class GoServerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Username or password rejected. */
    class InvalidCredentials(
        message: String = "Invalid username or password",
    ) : GoServerException(message)

    /** The stored session is no longer valid; a fresh sign-in is required. */
    class SessionExpired(
        message: String = "Session expired",
    ) : GoServerException(message)

    /** The server could not be reached or did not answer in time. */
    class Unreachable(
        message: String,
        cause: Throwable? = null,
    ) : GoServerException(message, cause)

    /** The server answered, but not in the shape this client understands. */
    class Protocol(
        message: String,
        cause: Throwable? = null,
    ) : GoServerException(message, cause)

    /** The server refuses further requests for now. */
    class RateLimited(
        message: String,
    ) : GoServerException(message)

    /** The game changed on the server since it was fetched (opponent moved, resigned, ...). */
    class StaleGame(
        message: String = "The game changed on the server",
    ) : GoServerException(message)

    /** The move itself was rejected (illegal position, ko, not your turn, ...). */
    class MoveRejected(
        val code: String,
        message: String,
    ) : GoServerException(message)

    /** Any other server-side error, with the server's own code for diagnostics. */
    class ServerError(
        val code: String,
        message: String,
    ) : GoServerException(message)
}
