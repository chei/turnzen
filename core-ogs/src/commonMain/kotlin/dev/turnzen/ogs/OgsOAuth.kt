package dev.turnzen.ogs

import dev.turnzen.ogs.net.OgsTransportException
import kotlin.io.encoding.Base64

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
        get() = base64Url(sha256(verifier.encodeToByteArray()))

    companion object {
        fun create(): Pkce = Pkce(verifier = randomToken(48), state = randomToken(16))

        private fun randomToken(bytes: Int): String = base64Url(secureRandomBytes(bytes))

        private fun base64Url(bytes: ByteArray): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    }
}

/** SHA-256 of [bytes]. Per platform: the standard library has no hashing. */
internal expect fun sha256(bytes: ByteArray): ByteArray

/** [count] bytes from the platform's cryptographically secure random source. */
internal expect fun secureRandomBytes(count: Int): ByteArray

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

/** Where the browser is sent once the user has answered OGS's sign-in page, and the wait for it. */
internal interface RedirectReceiver {
    val redirectUri: String

    /**
     * Runs [started] once the receiver is ready (that is when the browser may be sent off),
     * and waits for the redirect. Throws [OgsTransportException] when it cannot wait.
     */
    suspend fun await(started: () -> Unit): Redirect
}

/**
 * The platform's receiver. On the JVM (Android) it is [LoopbackRedirect][dev.turnzen.ogs.LoopbackRedirect],
 * a listener on 127.0.0.1; see there for why.
 */
internal expect fun redirectReceiver(
    port: Int,
    path: String,
    returnLink: String?,
): RedirectReceiver

/**
 * Form-style URL encoding, written out so it compiles for iOS as well as the JVM. Behaves like
 * `URLEncoder.encode` / `URLDecoder.decode` with UTF-8; `OgsUrlsTest` checks that against them.
 */
internal object OgsUrls {
    fun encode(value: String): String =
        buildString {
            for (byte in value.encodeToByteArray()) {
                val c = (byte.toInt() and 0xFF).toChar()
                when {
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_" -> append(c)
                    c == ' ' -> append('+')
                    else -> append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0x0F])
                }
            }
        }

    /** '+' is a space and %XX a byte of UTF-8; a broken escape throws [IllegalArgumentException]. */
    fun decode(value: String): String {
        val out = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '+' -> {
                    out += ' '.code.toByte()
                    i++
                }

                '%' -> {
                    require(i + 2 < value.length) { "Incomplete escape in \"$value\"" }
                    val hex = value.substring(i + 1, i + 3)
                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Bad escape %$hex" }
                    out += hex.toInt(16).toByte()
                    i += 3
                }

                else -> {
                    val end = if (c.isHighSurrogate() && i + 1 < value.length) i + 2 else i + 1
                    value.substring(i, end).encodeToByteArray().forEach { out += it }
                    i = end
                }
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** The parameters of a query string ("a=1&b=two"), decoded. */
    fun parseQuery(raw: String?): Map<String, String> =
        raw
            .orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair -> decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', "")) }

    private const val HEX = "0123456789ABCDEF"
}
