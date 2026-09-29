package dev.turnzen.dgs

import java.net.URI
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared URL helpers must answer exactly as the java.net calls they replaced. */
class UrlsTest {
    @Test
    fun `form encoding matches URLEncoder`() {
        listOf(
            "",
            "plain",
            "a b+c&d=e/f?g#h",
            "fake user",
            "p@ss:w0rd!~'()",
            "Ölveti Béla",
            "囲碁 ♚ 😀",
            ".-*_",
        ).forEach { value ->
            assertEquals(URLEncoder.encode(value, Charsets.UTF_8), Urls.formEncode(value), value)
        }
    }

    @Test
    fun `host matches URI`() {
        listOf(
            "https://www.dragongoserver.net/",
            "https://www.dragongoserver.net",
            "http://localhost:8080/dgs/",
            "https://user:pw@example.org:443/path?q=1#top",
            "http://127.0.0.1/",
            "http://[::1]:8080/",
        ).forEach { url ->
            assertEquals(URI(url).host, Urls.host(url), url)
        }
        assertEquals(null, Urls.host("not a url"))
    }
}
