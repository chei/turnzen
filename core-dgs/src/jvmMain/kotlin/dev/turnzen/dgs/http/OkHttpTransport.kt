package dev.turnzen.dgs.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production transport. Cookies are managed by [dev.turnzen.dgs.DgsClient], not by OkHttp.
 *
 * [userAgent] is installed as an interceptor rather than added per request, so no call
 * site can forget it: it holds for the QuickSuite, the login form, the SGF download and
 * the ladder page alike, and for any [baseClient] a caller hands in.
 */
class OkHttpTransport(
    userAgent: String,
    baseClient: OkHttpClient = defaultClient(),
) : HttpTransport {
    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
    }

    // First in the chain, so anything added later (logging, retries) sees the real agent.
    private val client =
        baseClient
            .newBuilder()
            .apply { interceptors().add(0, UserAgentInterceptor(userAgent)) }
            .build()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        when (request.method) {
            HttpMethod.GET -> {
                builder.get()
            }

            HttpMethod.POST -> {
                val form = FormBody.Builder()
                request.form.forEach { (k, v) -> form.add(k, v) }
                builder.post(form.build())
            }
        }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(HttpTransportException(e.message ?: "Request failed", e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            try {
                                response.use {
                                    HttpResponse(it.code, it.headers.toMultimap(), it.body.string())
                                }
                            } catch (e: IOException) {
                                continuation.resumeWithException(HttpTransportException(e.message ?: "Read failed", e))
                                return
                            }
                        continuation.resume(result)
                    }
                },
            )
        }
    }
}
