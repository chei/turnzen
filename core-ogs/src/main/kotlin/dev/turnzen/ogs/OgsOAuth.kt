package dev.turnzen.ogs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The pieces of an OAuth 2 authorization-code sign-in with PKCE (RFC 7636), as OGS offers it
 * to public clients: no client secret, a fresh one-time [verifier] per attempt instead.
 *
 * Only the SHA-256 of the verifier travels to the browser; the verifier itself is sent once,
 * directly, when the code is exchanged — so a code intercepted on its way back is useless to
 * anyone who does not also hold this object.
 */
class Pkce internal constructor(
    val verifier: String,
    /** Echoed back with the code; a mismatch means the answer is not for this attempt. */
    val state: String,
) {
    val challenge: String
        get() = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    companion object {
        private val random = SecureRandom()

        fun create(): Pkce = Pkce(verifier = randomToken(48), state = randomToken(16))

        private fun randomToken(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** What came back to the redirect address: a code, or the reason there is none. */
internal sealed interface Redirect {
    data class Code(
        val code: String,
        val state: String?,
    ) : Redirect

    data class Denied(
        val error: String,
    ) : Redirect
}

/**
 * The redirect address of the sign-in: a tiny HTTP listener on the phone's own loopback
 * interface, open only while a sign-in is under way.
 *
 * OGS accepts `http` redirects to local addresses but no custom URI schemes, and the other
 * route — an https App Link — would need a hosted `assetlinks.json` listing the fingerprint
 * of every key that signs the app (F-Droid's included). A loopback listener needs nothing
 * outside the app. The browser loads the redirect from 127.0.0.1, which only this device can
 * reach, and the page it gets back says the sign-in is done and offers the way back to the
 * app ([returnLink]).
 */
internal class LoopbackRedirect(
    private val port: Int,
    private val path: String,
    private val returnLink: String?,
) {
    val redirectUri: String
        get() = "http://127.0.0.1:$port$path"

    /**
     * Opens the listener, runs [started] once it is listening (that is when the browser may
     * be sent off), and waits for the redirect. Cancelling the coroutine closes the listener.
     */
    suspend fun await(started: () -> Unit): Redirect =
        withContext(Dispatchers.IO) {
            val server =
                try {
                    ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
                } catch (e: IOException) {
                    throw IOException("Port $port is taken on this device", e)
                }
            server.use {
                started()
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { runCatching { server.close() } }
                    try {
                        continuation.resumeWith(Result.success(acceptUntilRedirect(server)))
                    } catch (e: SocketException) {
                        // Closed by cancellation: the coroutine is already cancelled.
                        if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
                    } catch (e: IOException) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            }
        }

    /** Answers every request; the one on [path] ends the wait. A browser also asks for a favicon. */
    private fun acceptUntilRedirect(server: ServerSocket): Redirect {
        while (true) {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val requestLine =
                    socket
                        .getInputStream()
                        .bufferedReader(Charsets.US_ASCII)
                        .readLine()
                        .orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val uri = runCatching { URI(target) }.getOrNull()
                val out = socket.getOutputStream()
                if (uri?.path != path) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@use
                }
                val query = parseQuery(uri.rawQuery)
                val redirect =
                    query["code"]?.let { Redirect.Code(it, query["state"]) }
                        ?: Redirect.Denied(query["error"] ?: "no_code")
                val body = page(redirect is Redirect.Code).toByteArray(Charsets.UTF_8)
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                out.write(body)
                out.flush()
                return redirect
            }
        }
    }

    private fun page(ok: Boolean): String {
        val message = if (ok) "You are signed in to Online Go Server." else "The sign-in was cancelled."
        val link = returnLink?.let { """<p><a href="${escape(it)}">Back to Turnzen</a></p>""" }.orEmpty()
        return """<!doctype html><html><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width, initial-scale=1"><title>Turnzen</title>""" +
            """<style>body{font-family:sans-serif;margin:2rem;line-height:1.5}a{font-size:1.2rem}</style></head>""" +
            """<body><p>$message</p>$link</body></html>"""
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    companion object {
        fun parseQuery(raw: String?): Map<String, String> =
            raw
                .orEmpty()
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
                }

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
