package dev.turnzen.ogs

import dev.turnzen.ogs.net.OgsTransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private val random = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

internal actual fun redirectReceiver(
    port: Int,
    path: String,
    returnLink: String?,
): RedirectReceiver = LoopbackRedirect(port, path, returnLink)

/**
 * The redirect address of the sign-in: a tiny HTTP listener on the phone's own loopback
 * interface, open only while a sign-in is under way.
 *
 * OGS accepts `http` redirects to local addresses but no custom URI schemes, and the other
 * route — an https App Link — would need a hosted `assetlinks.json` listing the fingerprint
 * of every key that signs the app (F-Droid's included). A loopback listener needs nothing
 * outside the app. The browser loads the redirect from 127.0.0.1, which only this device can
 * reach. With a [returnLink] the answer is a redirect straight back to the app, so the user
 * never sees a page of ours; the browser follows it because the chain of navigations began
 * with the user's own tap on OGS's authorize button. Without one, the answer is a page saying
 * the sign-in is done.
 */
internal class LoopbackRedirect(
    private val port: Int,
    private val path: String,
    private val returnLink: String?,
) : RedirectReceiver {
    override val redirectUri: String
        get() = "http://127.0.0.1:$port$path"

    /**
     * Opens the listener, runs [started] once it is listening (that is when the browser may
     * be sent off), and waits for the redirect. Cancelling the coroutine closes the listener.
     */
    override suspend fun await(started: () -> Unit): Redirect =
        try {
            listen(started)
        } catch (e: IOException) {
            throw OgsTransportException(e.message ?: "The sign-in listener failed", e)
        }

    private suspend fun listen(started: () -> Unit): Redirect =
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
                val query = OgsUrls.parseQuery(uri.rawQuery)
                val redirect =
                    query["code"]?.let { Redirect.Code(it, query["state"]) }
                        ?: Redirect.Denied(query["error"] ?: "no_code")
                val body = page(redirect is Redirect.Code).toByteArray(Charsets.UTF_8)
                val status = if (returnLink == null) "200 OK" else "302 Found\r\nLocation: $returnLink"
                out.write(
                    (
                        "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
                )
                out.write(body)
                out.flush()
                return redirect
            }
        }
    }

    /** The body; with a [returnLink] it is only seen by a browser that will not follow the redirect. */
    private fun page(ok: Boolean): String {
        val message = if (ok) "You are signed in to Online Go Server." else "The sign-in was cancelled."
        val link = returnLink?.let { """<p><a href="${escape(it)}">Back to Turnzen</a></p>""" }.orEmpty()
        return """<!doctype html><html><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width, initial-scale=1"><title>Turnzen</title>""" +
            """<style>body{font-family:sans-serif;margin:2rem;line-height:1.5}a{font-size:1.2rem}</style></head>""" +
            """<body><p>$message</p>$link</body></html>"""
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
