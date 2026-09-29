package dev.turnzen.ogs

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The shared helpers must answer exactly as the java.* calls they replaced. */
class OgsUrlsTest {
    private val samples =
        listOf(
            "",
            "plain",
            "a b+c&d=e/f?g#h",
            "read write",
            "http://127.0.0.1:8735/ogs/signed-in",
            "Ölveti",
            "囲碁 😀",
            ".-*_~",
        )

    @Test
    fun `encoding matches URLEncoder`() {
        samples.forEach { assertEquals(URLEncoder.encode(it, Charsets.UTF_8), OgsUrls.encode(it), it) }
    }

    @Test
    fun `decoding matches URLDecoder`() {
        (
            samples.map { URLEncoder.encode(it, Charsets.UTF_8) } +
                listOf("a+b", "%41%62c", "%e5%9b%b2", "plain~text", "😀")
        ).forEach { assertEquals(URLDecoder.decode(it, Charsets.UTF_8), OgsUrls.decode(it), it) }
    }

    @Test
    fun `a broken escape is rejected, as URLDecoder does`() {
        listOf("%", "%4", "abc%zz", "%-1").forEach { value ->
            assertFailsWith<IllegalArgumentException> { URLDecoder.decode(value, Charsets.UTF_8) }
            assertFailsWith<IllegalArgumentException>(value) { OgsUrls.decode(value) }
        }
    }

    @Test
    fun `a query splits into decoded pairs`() {
        assertEquals(
            mapOf("code" to "a b", "state" to "x/y", "empty" to ""),
            OgsUrls.parseQuery("code=a+b&state=x%2Fy&empty="),
        )
    }

    @Test
    fun `the PKCE challenge is the unpadded base64url SHA-256 of the verifier`() {
        val pkce = Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "s")
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        val javaStyle =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray()),
            )
        assertEquals(javaStyle, pkce.challenge)
        val fresh = Pkce.create()
        assertEquals(64, fresh.verifier.length)
        assertEquals(22, fresh.state.length)
    }
}
