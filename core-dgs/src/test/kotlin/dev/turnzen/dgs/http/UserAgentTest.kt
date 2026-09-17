package dev.turnzen.dgs.http

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentTest {
    private val userAgent = TurnzenUserAgent.format("0.1.0", 36, "https://github.com/chei/turnzen")

    @Test
    fun `the agent names the app, its version, the device api level and the project`() {
        assertEquals("Turnzen/0.1.0 (Android 36; +https://github.com/chei/turnzen)", userAgent)
    }

    /**
     * The last link of the chain answers instead of dialling out, so the assertions are on
     * the request OkHttp would really have put on the wire, with no network in the test.
     */
    private class Terminator : Interceptor {
        val seen = ArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            seen += chain.request()
            return Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()
        }
    }

    private fun transportWith(terminator: Terminator) =
        OkHttpTransport(userAgent, OkHttpClient.Builder().addInterceptor(terminator).build())

    @Test
    fun `every request carries the agent, whatever it is for`() =
        runTest {
            val terminator = Terminator()
            val transport = transportWith(terminator)

            transport.execute(HttpRequest(HttpMethod.GET, "https://dgs.example.test/quick_do.php?obj=user&cmd=info"))
            transport.execute(HttpRequest(HttpMethod.GET, "https://dgs.example.test/sgf.php?gid=1000000"))
            transport.execute(
                HttpRequest(
                    HttpMethod.POST,
                    "https://dgs.example.test/login.php",
                    form = mapOf("userid" to "fakeuser", "passwd" to "secret"),
                ),
            )

            assertEquals(3, terminator.seen.size)
            terminator.seen.forEach { assertEquals(userAgent, it.header("User-Agent"), "missing on ${it.url}") }
        }

    @Test
    fun `the agent the transport sets wins over one a caller put on the request`() =
        runTest {
            val terminator = Terminator()
            transportWith(terminator).execute(
                HttpRequest(
                    HttpMethod.GET,
                    "https://dgs.example.test/quick_do.php",
                    headers = mapOf("User-Agent" to "okhttp/5.5.0", "Cookie" to "cookie_sessioncode=abc123"),
                ),
            )

            val request = terminator.seen.single()
            assertEquals(userAgent, request.header("User-Agent"))
            assertEquals("cookie_sessioncode=abc123", request.header("Cookie"), "other headers are left alone")
        }
}
