package dev.turnzen.ogs.net

/**
 * The HTTP surface the OGS client needs: plain GETs, form POSTs (OAuth) and JSON POSTs
 * (the REST API). An interface so the client is testable on the JVM with canned answers.
 *
 * Deliberately not shared with `:core-dgs`: server modules depend on `:core-goserver`
 * only, and the two servers want different things from HTTP (cookies there, bearer
 * tokens and JSON bodies here).
 */
interface OgsHttp {
    suspend fun execute(request: OgsRequest): OgsResponse
}

enum class OgsMethod { GET, POST }

data class OgsRequest(
    val method: OgsMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Form fields for a POST (application/x-www-form-urlencoded). Ignored when [json] is set. */
    val form: Map<String, String> = emptyMap(),
    /** A JSON body for a POST. */
    val json: String? = null,
)

class OgsResponse(
    val status: Int,
    val body: String,
)

/** Thrown by transports for connection-level failures (DNS, timeouts, TLS). */
class OgsTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
