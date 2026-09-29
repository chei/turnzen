package dev.turnzen.dgs.http

/**
 * The tiny HTTP surface the DGS client needs. Kept as an interface so parsers and the
 * client logic are testable on the JVM with canned responses and no network.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

enum class HttpMethod { GET, POST }

data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Form fields for POST (application/x-www-form-urlencoded). */
    val form: Map<String, String> = emptyMap(),
)

class HttpResponse(
    val status: Int,
    headers: Map<String, List<String>>,
    val body: String,
) {
    /** Header names are matched case-insensitively. */
    val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): List<String> = headers[name.lowercase()] ?: emptyList()
}

/** Thrown by transports for connection-level failures (DNS, timeouts, TLS). */
class HttpTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
