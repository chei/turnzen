package dev.turnzen.goserver

/** Which half of a sign-in the server rejected, so the UI can say something useful. */
enum class CredentialProblem {
    /** No account with that username exists. */
    UNKNOWN_USER,

    /** The account exists; the password does not match. */
    WRONG_PASSWORD,

    /** The account was registered but never confirmed by email. */
    NOT_ACTIVATED,

    /** The server refuses sign-ins for this account or from this address. */
    BLOCKED,

    /** The server rejected the credentials without saying more. */
    UNSPECIFIED,
}

/** Failures a server client reports. The UI maps these to copy; it never sees raw responses. */
sealed class GoServerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Username or password rejected. [problem] says which, when the server is specific. */
    class InvalidCredentials(
        val problem: CredentialProblem = CredentialProblem.UNSPECIFIED,
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

    /** The server answered, but only to say it cannot serve requests now (HTTP 5xx: down, maintenance). */
    class Unavailable(
        message: String,
    ) : GoServerException(message)

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
