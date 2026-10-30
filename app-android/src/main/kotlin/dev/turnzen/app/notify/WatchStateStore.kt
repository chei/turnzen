package dev.turnzen.app.notify

import android.content.Context
import android.content.SharedPreferences
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.ServerId

/**
 * Keeps [WatchState] between checks. The background check and the app on screen both
 * change it, from different threads, so every change goes through [update].
 */
class WatchStateStore internal constructor(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences("turnzen_notifications", Context.MODE_PRIVATE))

    @Synchronized
    fun read(): WatchState =
        WatchState(
            known = moves(KEY_KNOWN),
            flagged = ids(KEY_FLAGGED),
            lowAlerted = moves(KEY_LOW_ALERTED),
            signedOutNoticed =
                prefs
                    .getStringSet(KEY_SIGNED_OUT, emptySet())
                    .orEmpty()
                    .map(::ServerId)
                    .toSet(),
        )

    @Synchronized
    fun update(transform: (WatchState) -> WatchState): WatchState {
        val next = transform(read())
        prefs
            .edit()
            .putStringSet(KEY_KNOWN, next.known.map { (id, move) -> encode(id) + SEP + move }.toSet())
            .putStringSet(KEY_FLAGGED, next.flagged.map(::encode).toSet())
            .putStringSet(KEY_LOW_ALERTED, next.lowAlerted.map { (id, move) -> encode(id) + SEP + move }.toSet())
            .putStringSet(KEY_SIGNED_OUT, next.signedOutNoticed.map { it.value }.toSet())
            .apply()
        return next
    }

    @Synchronized
    fun readMessages(): MessageWatchState =
        MessageWatchState(known = messageIds(KEY_MESSAGES_KNOWN), flagged = messageIds(KEY_MESSAGES_FLAGGED))

    /** The messages' state, kept beside the games' in the same file and under the same lock. */
    @Synchronized
    fun updateMessages(transform: (MessageWatchState) -> MessageWatchState): MessageWatchState {
        val next = transform(readMessages())
        prefs
            .edit()
            .putStringSet(KEY_MESSAGES_KNOWN, next.known.map { it.server.value + SEP + it.value }.toSet())
            .putStringSet(KEY_MESSAGES_FLAGGED, next.flagged.map { it.server.value + SEP + it.value }.toSet())
            .apply()
        return next
    }

    private fun messageIds(key: String): Set<MessageId> =
        prefs
            .getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split(SEP)
                if (parts.size == 2) MessageId(ServerId(parts[0]), parts[1]) else null
            }.toSet()

    private fun moves(key: String): Map<GameId, Int> =
        prefs
            .getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split(SEP)
                if (parts.size != 3) return@mapNotNull null
                val move = parts[2].toIntOrNull() ?: return@mapNotNull null
                GameId(ServerId(parts[0]), parts[1]) to move
            }.toMap()

    private fun ids(key: String): Set<GameId> =
        prefs
            .getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split(SEP)
                if (parts.size == 2) GameId(ServerId(parts[0]), parts[1]) else null
            }.toSet()

    private fun encode(id: GameId): String = id.server.value + SEP + id.value

    private companion object {
        const val SEP = "\t"
        const val KEY_KNOWN = "known"
        const val KEY_FLAGGED = "flagged"
        const val KEY_LOW_ALERTED = "low_alerted"
        const val KEY_SIGNED_OUT = "signed_out_noticed"
        const val KEY_MESSAGES_KNOWN = "messages_known"
        const val KEY_MESSAGES_FLAGGED = "messages_flagged"
    }
}
