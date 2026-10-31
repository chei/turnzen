package dev.turnzen.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.ServerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The games on the running lists that wait for a first look: the ones that have just begun
 * and the ones that have just ended. Each is marked on its row until the user opens it.
 */
data class GameMarks(
    /** Games that came onto the running list after the first load, not opened since. */
    val unopened: Set<GameId> = emptySet(),
    /**
     * Games that left the running list because they ended, as the archive describes them
     * (result and all). They stay on the running lists until opened, newest first.
     */
    val ended: List<GameSummary> = emptyList(),
)

/**
 * Where [GameMarks] are kept between runs of the app: a mark stands until the game is
 * opened, which may well be in a later run.
 */
interface GameMarksStore {
    fun load(): GameMarks

    fun save(marks: GameMarks)

    /** No persistence: the marks live and die with the process. */
    object None : GameMarksStore {
        override fun load(): GameMarks = GameMarks()

        override fun save(marks: GameMarks) = Unit
    }
}

/** [GameMarksStore] in the app's private preferences, as one JSON entry. */
class PrefsGameMarksStore internal constructor(
    private val prefs: SharedPreferences,
) : GameMarksStore {
    constructor(context: Context) : this(context.getSharedPreferences("turnzen_game_marks", Context.MODE_PRIVATE))

    override fun load(): GameMarks = prefs.getString(KEY, null)?.let(GameMarksCodec::decode) ?: GameMarks()

    override fun save(marks: GameMarks) = prefs.edit().putString(KEY, GameMarksCodec.encode(marks)).apply()

    private companion object {
        const val KEY = "marks"
    }
}

/**
 * The stored form of [GameMarks], built on [GamesCodec]'s summary format. As there, anything
 * that does not read back is a miss: the worst it costs is a mark that does not show.
 */
internal object GameMarksCodec {
    private const val VERSION = 1

    fun encode(marks: GameMarks): String =
        buildJsonObject {
            put("v", VERSION)
            put(
                "unopened",
                buildJsonArray {
                    marks.unopened.forEach { id ->
                        add(
                            buildJsonObject {
                                put("server", id.server.value)
                                put("id", id.value)
                            },
                        )
                    }
                },
            )
            put(
                "ended",
                buildJsonArray {
                    marks.ended.forEach { game ->
                        add(JsonObject(GamesCodec.game(game) + ("server" to JsonPrimitive(game.id.server.value))))
                    }
                },
            )
        }.toString()

    fun decode(text: String): GameMarks? =
        try {
            val root = Json.parseToJsonElement(text) as JsonObject
            if (root.long("v") != VERSION.toLong()) {
                null
            } else {
                GameMarks(
                    unopened =
                        (root["unopened"] as JsonArray)
                            .map { it as JsonObject }
                            .mapTo(LinkedHashSet()) { GameId(ServerId(it.string("server")!!), it.string("id")!!) },
                    ended =
                        (root["ended"] as JsonArray)
                            .map { it as JsonObject }
                            .map { GamesCodec.summary(ServerId(it.string("server")!!), it) },
                )
            }
        } catch (e: RuntimeException) {
            null
        }
}
