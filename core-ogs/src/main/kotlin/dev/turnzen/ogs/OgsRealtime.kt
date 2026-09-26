package dev.turnzen.ogs

import dev.turnzen.goserver.GoServerException
import dev.turnzen.ogs.net.OgsSocket
import dev.turnzen.ogs.net.OgsSocketConnector
import dev.turnzen.ogs.net.OgsTransportException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The OGS realtime connection, used the way a correspondence client needs it: opened for
 * one action on one game, and closed again. Some things exist nowhere else — resigning, for
 * one — so even a pull-based client has to speak it briefly.
 *
 * Frames are JSON arrays. A request is `[command, data, id]` and its answer `[id, data, error]`;
 * everything the server pushes on its own is `[event, data]`, with game events named
 * `game/<id>/<what>`.
 */
internal class OgsRealtime(
    private val connector: OgsSocketConnector,
    private val url: String,
    private val clientVersion: String,
    private val timeoutMillis: Long = 20_000,
) {
    /**
     * Connects, authenticates with [jwt], joins [gameId] and runs [block] with the game data
     * the server sends on joining; leaves and disconnects whatever happens.
     */
    suspend fun <T> inGame(
        jwt: String,
        gameId: Long,
        block: suspend GameConnection.(gamedata: JsonObject) -> T,
    ): T {
        val socket =
            try {
                connector.connect(url)
            } catch (e: OgsTransportException) {
                throw GoServerException.Unreachable("Couldn't reach Online Go Server", e)
            }
        try {
            val connection = GameConnection(socket, gameId)
            connection.authenticate(jwt)
            connection.send(
                "game/connect",
                buildJsonObject {
                    put("game_id", gameId)
                    put("chat", false)
                },
            )
            val gamedata =
                connection.awaitEvent("gamedata") as? JsonObject
                    ?: throw GoServerException.Protocol("Online Go Server sent no game data for game $gameId")
            return connection.block(gamedata)
        } finally {
            runCatching {
                GameConnection(socket, gameId).send("game/disconnect", buildJsonObject { put("game_id", gameId) })
            }
            socket.close()
        }
    }

    inner class GameConnection internal constructor(
        private val socket: OgsSocket,
        val gameId: Long,
    ) {
        private var lastRequestId = 0

        fun send(
            command: String,
            data: JsonObject,
            id: Int? = null,
        ) {
            val frame =
                buildJsonArray {
                    add(JsonPrimitive(command))
                    add(data)
                    if (id != null) add(JsonPrimitive(id))
                }
            socket.send(frame.toString())
        }

        suspend fun authenticate(jwt: String) {
            val id = ++lastRequestId
            send(
                "authenticate",
                buildJsonObject {
                    put("jwt", jwt)
                    put("client", "turnzen")
                    put("client_version", clientVersion)
                },
                id,
            )
            val (_, error) = awaitReply(id)
            if (error != null && error !is JsonNull) {
                throw GoServerException.SessionExpired("Online Go Server refused the realtime sign-in")
            }
        }

        /** The data of the next `game/<id>/<name>` event, or a failure if the server reports an error first. */
        suspend fun awaitEvent(vararg names: String): JsonElement = awaitAnyEvent(*names).second

        /** Which of [names] came first, and its data. A `game/<id>/error` event fails the wait. */
        suspend fun awaitAnyEvent(vararg names: String): Pair<String, JsonElement> {
            val prefix = "game/$gameId/"
            while (true) {
                val frame = nextFrame()
                val command = (frame.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                if (!command.startsWith(prefix)) continue
                val name = command.removePrefix(prefix)
                val data = frame.getOrNull(1) ?: JsonNull
                if (name == "error") {
                    val message = (data as? JsonPrimitive)?.content ?: data.toString()
                    throw GoServerException.MoveRejected(code = message, message = message)
                }
                if (name in names) return name to data
            }
        }

        private suspend fun awaitReply(id: Int): Pair<JsonElement?, JsonElement?> {
            while (true) {
                val frame = nextFrame()
                val first = frame.getOrNull(0) as? JsonPrimitive ?: continue
                if (first.isString || first.content != id.toString()) continue
                return frame.getOrNull(1) to frame.getOrNull(2)
            }
        }

        private suspend fun nextFrame(): JsonArray {
            val text =
                try {
                    withTimeout(timeoutMillis) { socket.incoming.receive() }
                } catch (e: TimeoutCancellationException) {
                    throw GoServerException.Unreachable("Online Go Server didn't answer in time")
                } catch (e: ClosedReceiveChannelException) {
                    throw GoServerException.Unreachable("Online Go Server closed the connection")
                } catch (e: OgsTransportException) {
                    throw GoServerException.Unreachable("Lost the connection to Online Go Server", e)
                }
            return runCatching { OgsJson.json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: JsonArray(emptyList())
        }
    }
}
