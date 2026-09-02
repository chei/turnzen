package dev.turnzen.goserver

import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Move
import kotlinx.coroutines.flow.Flow

/**
 * Placeholder for real-time servers (OGS live games and the like). Not implemented in
 * the MVP; it exists so the module boundaries do not need rework when live play arrives.
 * Expect this shape to change once a first implementation exists.
 */
interface LiveGoServerClient {
    val serverName: String

    suspend fun connect(credentials: Credentials)

    suspend fun disconnect()

    /** Subscribes to a running game; emits every move and clock update as it happens. */
    fun observe(id: GameId): Flow<LiveGameEvent>

    suspend fun sendMove(
        id: GameId,
        move: Move,
    )
}

sealed interface LiveGameEvent {
    data class MovePlayed(
        val move: ColoredMove,
    ) : LiveGameEvent

    data class ClockUpdated(
        val blackMillis: Long,
        val whiteMillis: Long,
    ) : LiveGameEvent

    data class Ended(
        val result: String,
    ) : LiveGameEvent
}
