package dev.turnzen.dgs.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Stamps [userAgent] on every outgoing request.
 *
 * Without it OkHttp sends its own default, so our traffic lands in the server's public
 * webalizer stats as a nameless `okhttp/5.x` line, pooled with every other unlabeled
 * client. A named agent with a version and a link back to the project makes Turnzen's
 * share of the load identifiable and attributable, and gives the admins a version to
 * name and somewhere to complain if a release starts misbehaving.
 *
 * It carries app identity only — no handle, no user id, no session token. Those travel
 * in the Cookie header, where they belong.
 */
internal class UserAgentInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent)
                .build(),
        )
}
