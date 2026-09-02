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

/** Production transport. Cookies are managed by [dev.turnzen.dgs.DgsClient], not by OkHttp. */
class OkHttpTransport(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
) : HttpTransport {
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
