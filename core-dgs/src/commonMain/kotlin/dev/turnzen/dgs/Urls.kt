package dev.turnzen.dgs

/**
 * The two URL helpers the client needs, written out so they compile for iOS as well as the
 * JVM (java.net is JVM-only). They behave like `URLEncoder.encode(value, UTF_8)` and
 * `URI(url).host`; `UrlsTest` checks that against the Java originals.
 */
internal object Urls {
    /** `application/x-www-form-urlencoded`: space as '+', everything but `A-Za-z0-9.-*_` as %XX of its UTF-8. */
    fun formEncode(value: String): String =
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

    /** The host part of an absolute URL ("https://www.example.net/x" -> "www.example.net"); null without one. */
    fun host(url: String): String? {
        val afterScheme = url.substringAfter("://", "").ifEmpty { return null }
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostAndPort = authority.substringAfterLast('@')
        val host =
            if (hostAndPort.startsWith('[')) {
                hostAndPort.substringBefore(']') + "]"
            } else {
                hostAndPort.substringBefore(':')
            }
        return host.ifEmpty { null }
    }

    private const val HEX = "0123456789ABCDEF"
}
