package dev.turnzen.dgs

/**
 * The DGS login cookies. Deliberately simple: name/value pairs for one host, no
 * expiry handling; the server tells us when the session is gone (`not_logged_in`).
 */
class SessionCookies private constructor(
    private val values: LinkedHashMap<String, String>,
) {
    constructor() : this(LinkedHashMap())

    val isEmpty: Boolean
        get() = values.isEmpty()

    fun absorb(setCookieHeaders: List<String>) {
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (value.isEmpty() ||
                value.equals("deleted", ignoreCase = true)
            ) {
                values.remove(name)
            } else {
                values[name] = value
            }
        }
    }

    fun clear() = values.clear()

    fun headerValue(): String? =
        if (values.isEmpty()) {
            null
        } else {
            values.entries.joinToString("; ") {
                "${it.key}=${it.value}"
            }
        }

    fun serialize(): String = headerValue() ?: ""

    companion object {
        fun deserialize(text: String): SessionCookies {
            val cookies = SessionCookies()
            text
                .split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { cookies.absorb(listOf(it)) }
            return cookies
        }
    }
}
