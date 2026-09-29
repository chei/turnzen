package dev.turnzen.ogs.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production HTTP and websocket transport for OGS, on one OkHttp client.
 *
 * [userAgent] is the app-wide Turnzen agent (see `TurnzenUserAgent` in the app), stamped on
 * every request and on the websocket handshake alike. It carries app identity only; the
 * account travels in the Authorization header.
 */
class OkHttpOgs(
    userAgent: String,
    baseClient: OkHttpClient = defaultClient(),
) : OgsHttp,
    OgsSocketConnector {
    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client =
        baseClient
            .newBuilder()
            .apply {
                interceptors().add(0) { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header("User-Agent", userAgent)
                            .build(),
                    )
                }
            }.build()

    override suspend fun execute(request: OgsRequest): OgsResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        when (request.method) {
            OgsMethod.GET -> {
                builder.get()
            }

            OgsMethod.POST -> {
                if (request.json != null) {
                    builder.post(request.json.toRequestBody(JSON))
                } else {
                    val form = FormBody.Builder()
                    request.form.forEach { (k, v) -> form.add(k, v) }
                    builder.post(form.build())
                }
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
                        continuation.resumeWithException(OgsTransportException(e.message ?: "Request failed", e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            try {
                                response.use { OgsResponse(it.code, it.body.string()) }
                            } catch (e: IOException) {
                                continuation.resumeWithException(OgsTransportException(e.message ?: "Read failed", e))
                                return
                            }
                        continuation.resume(result)
                    }
                },
            )
        }
    }

    override suspend fun connect(url: String): OgsSocket {
        val opened = CompletableDeferred<Unit>()
        val frames = Channel<String>(Channel.UNLIMITED)
        val socket =
            client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        opened.complete(Unit)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        frames.trySend(text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                        frames.close()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        val failure = OgsTransportException(t.message ?: "Websocket failed", t)
                        opened.completeExceptionally(failure)
                        frames.close(failure)
                    }
                },
            )
        try {
            opened.await()
        } catch (e: Throwable) {
            socket.cancel()
            throw e
        }
        return object : OgsSocket {
            override val incoming: ReceiveChannel<String> = frames

            override fun send(frame: String) {
                socket.send(frame)
            }

            override fun close() {
                socket.close(1000, null)
                frames.close()
            }
        }
    }
}
