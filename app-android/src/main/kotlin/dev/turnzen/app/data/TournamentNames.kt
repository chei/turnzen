package dev.turnzen.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.TournamentRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The names of the tournaments the user's games were played for, kept across app restarts.
 *
 * The game lists carry a tournament's id and nothing more; its name comes with a game's full
 * record (on DGS, out of the SGF header). But a finished game's record is dropped as soon as
 * the game stops running, so by the time its result arrives the name would be gone with it.
 * Remembering each name the first time a record shows it lets the result say "DGS Ladder
 * (19x19)" rather than "Tournament #3". A handful of entries, a few dozen bytes each.
 *
 * Keyed by server and id: a server's tournaments share one id space. Only what is true of the
 * whole tournament is kept — not the round, which belongs to one game of it.
 */
interface TournamentNames {
    /** [ref] with the name and kind filled in where they are known; [ref] itself otherwise. */
    fun resolve(
        server: ServerId,
        ref: TournamentRef,
    ): TournamentRef

    fun remember(
        server: ServerId,
        ref: TournamentRef,
    )

    object None : TournamentNames {
        override fun resolve(
            server: ServerId,
            ref: TournamentRef,
        ) = ref

        override fun remember(
            server: ServerId,
            ref: TournamentRef,
        ) = Unit
    }
}

/** [TournamentNames] in the app's private preferences, one JSON entry per tournament. */
class PrefsTournamentNames internal constructor(
    private val prefs: SharedPreferences,
) : TournamentNames {
    constructor(context: Context) : this(context.getSharedPreferences("turnzen_tournaments", Context.MODE_PRIVATE))

    override fun resolve(
        server: ServerId,
        ref: TournamentRef,
    ): TournamentRef {
        if (ref.name != null) return ref
        val stored =
            prefs.getString(key(server, ref.id), null)?.let {
                try {
                    Json.parseToJsonElement(it) as? JsonObject
                } catch (e: RuntimeException) {
                    null
                }
            } ?: return ref
        return ref.copy(
            name = stored.string("name"),
            kind = stored.string("kind") ?: ref.kind,
            isLadder = ref.isLadder || stored.boolean("isLadder") == true,
        )
    }

    override fun remember(
        server: ServerId,
        ref: TournamentRef,
    ) {
        val name = ref.name ?: return
        val entry =
            buildJsonObject {
                put("name", name)
                put("kind", ref.kind)
                if (ref.isLadder) put("isLadder", true)
            }.toString()
        val key = key(server, ref.id)
        if (prefs.getString(key, null) != entry) prefs.edit().putString(key, entry).apply()
    }

    private fun key(
        server: ServerId,
        id: String,
    ) = "${server.value}/$id"
}
