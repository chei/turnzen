package dev.turnzen.ogs.net

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One open websocket: text frames out, text frames in. [incoming] closes when the
 * connection does, with the failure as its cause when it broke rather than ended.
 */
interface OgsSocket {
    val incoming: ReceiveChannel<String>

    fun send(frame: String)

    fun close()
}

/** Opens [OgsSocket]s; suspends until the connection is up, or throws [OgsTransportException]. */
fun interface OgsSocketConnector {
    suspend fun connect(url: String): OgsSocket
}
