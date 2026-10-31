package dev.turnzen.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.turnzen.game.GameResult
import dev.turnzen.game.ResultReason
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.ServerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** One server's running lists as it last gave them, and when. */
data class CachedLists(
    val fetchedAt: Instant,
    val yourTurn: List<GameSummary>,
    val allRunning: List<GameSummary>,
)

/**
 * The running-games lists, kept across app restarts so a cold start shows the games at once,
 * and still shows them when a server cannot be reached. Only the running lists: the archive
 * is fetched when its tab is opened, as before.
 *
 * Per server, because each server answers (or fails) on its own and the screen says how old
 * each server's part of the list is.
 */
interface GamesCache {
    fun load(): Map<ServerId, CachedLists>

    fun save(
        server: ServerId,
        lists: CachedLists,
    )

    fun forget(server: ServerId)

    fun clear()

    /** No persistence: the lists live and die with the process. */
    object None : GamesCache {
        override fun load(): Map<ServerId, CachedLists> = emptyMap()

        override fun save(
            server: ServerId,
            lists: CachedLists,
        ) = Unit

        override fun forget(server: ServerId) = Unit

        override fun clear() = Unit
    }
}

/**
 * [GamesCache] in the app's private preferences: an entry per server, its lists as JSON. Not
 * readable by other apps. A few dozen games come to some tens of kilobytes.
 */
class PrefsGamesCache internal constructor(
    private val prefs: SharedPreferences,
) : GamesCache {
    constructor(context: Context) : this(context.getSharedPreferences("turnzen_games", Context.MODE_PRIVATE))

    override fun load(): Map<ServerId, CachedLists> =
        prefs.all
            .mapNotNull { (key, value) ->
                val lists = (value as? String)?.let { GamesCodec.decode(ServerId(key), it) } ?: return@mapNotNull null
                ServerId(key) to lists
            }.toMap()

    override fun save(
        server: ServerId,
        lists: CachedLists,
    ) = prefs.edit().putString(server.value, GamesCodec.encode(lists)).apply()

    override fun forget(server: ServerId) = prefs.edit().remove(server.value).apply()

    override fun clear() = prefs.edit().clear().apply()
}

/**
 * The stored form of [CachedLists]. Built by hand on the JSON tree rather than with the
 * serialization plugin: a dozen fields that never leave this file do not earn a compiler
 * plugin. Anything that does not read back — an older format, a damaged entry — is a miss,
 * never an error: the next refresh writes it again.
 *
 * The running lists hold running games only; a finished game's fields (its result and rating
 * change) are written for [GameMarks.ended], and left out when they are empty.
 */
internal object GamesCodec {
    private const val VERSION = 1

    fun encode(lists: CachedLists): String =
        buildJsonObject {
            put("v", VERSION)
            put("fetchedAt", lists.fetchedAt.toString())
            put("yourTurn", buildJsonArray { lists.yourTurn.forEach { add(game(it)) } })
            put("allRunning", buildJsonArray { lists.allRunning.forEach { add(game(it)) } })
        }.toString()

    fun decode(
        server: ServerId,
        text: String,
    ): CachedLists? =
        try {
            val root = Json.parseToJsonElement(text) as JsonObject
            if (root.long("v") != VERSION.toLong()) {
                null
            } else {
                CachedLists(
                    fetchedAt = root.instant("fetchedAt") ?: return null,
                    yourTurn = (root["yourTurn"] as JsonArray).map { summary(server, it as JsonObject) },
                    allRunning = (root["allRunning"] as JsonArray).map { summary(server, it as JsonObject) },
                )
            }
        } catch (e: RuntimeException) {
            // Malformed JSON, a missing field, an enum value this build does not know.
            null
        }

    fun game(g: GameSummary): JsonObject =
        buildJsonObject {
            put("id", g.id.value)
            put("handle", g.opponent.handle)
            put("displayName", g.opponent.displayName)
            put("rank", g.opponent.rank)
            put("myColor", g.myColor.name)
            put("boardSize", g.boardSize)
            put("boardHeight", g.boardHeight)
            put("handicap", g.handicap)
            put("moveNumber", g.moveNumber)
            put("lastMoveAt", g.lastMoveAt?.toString())
            g.remainingTime?.let { put("remainingTime", remainingTime(it)) }
            put("pendingAction", g.pendingAction.name)
            put("isMyTurn", g.isMyTurn)
            put("moveToken", g.moveToken.value)
            put("onlyOnWeb", g.onlyOnWeb)
            if (g.isFinished) put("isFinished", true)
            g.result?.let { result ->
                put(
                    "result",
                    buildJsonObject {
                        put("winner", result.winner?.name)
                        put("margin", result.margin)
                        put("by", result.by.name)
                    },
                )
            }
            g.ratingChange?.let { put("ratingChange", it) }
        }

    fun summary(
        server: ServerId,
        o: JsonObject,
    ): GameSummary =
        GameSummary(
            id = GameId(server, o.string("id")!!),
            opponent = Opponent(o.string("handle")!!, o.string("displayName"), o.string("rank")),
            myColor = StoneColor.valueOf(o.string("myColor")!!),
            boardSize = o.int("boardSize")!!,
            boardHeight = o.int("boardHeight")!!,
            handicap = o.int("handicap")!!,
            moveNumber = o.int("moveNumber")!!,
            lastMoveAt = o.instant("lastMoveAt"),
            remainingTime =
                (o["remainingTime"] as? JsonObject)?.let(::remainingTime),
            pendingAction = PendingAction.valueOf(o.string("pendingAction")!!),
            isMyTurn = o.boolean("isMyTurn")!!,
            moveToken = MoveToken(o.string("moveToken")!!),
            onlyOnWeb = o.boolean("onlyOnWeb") ?: false,
            isFinished = o.boolean("isFinished") ?: false,
            result =
                (o["result"] as? JsonObject)?.let { r ->
                    GameResult(
                        winner = r.string("winner")?.let(StoneColor::valueOf),
                        margin = r.double("margin"),
                        by = ResultReason.valueOf(r.string("by")!!),
                    )
                },
            ratingChange = o.double("ratingChange"),
        )

    fun remainingTime(time: RemainingTime): JsonObject =
        buildJsonObject {
            put("text", time.text)
            put("main", time.main?.inWholeMilliseconds)
            put("extra", time.extra)
        }

    fun remainingTime(o: JsonObject): RemainingTime =
        RemainingTime(o.string("text")!!, o.long("main")?.milliseconds, o.string("extra"))
}

// Readers for the stored JSON. Strict: a value of the wrong kind throws, and the entry is a miss.

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    when (val v: JsonElement? = this[key]) {
        null, JsonNull -> null
        else -> v as JsonPrimitive
    }

internal fun JsonObject.string(key: String): String? = primitive(key)?.content

internal fun JsonObject.long(key: String): Long? = primitive(key)?.content?.toLong()

internal fun JsonObject.int(key: String): Int? = primitive(key)?.content?.toInt()

internal fun JsonObject.double(key: String): Double? = primitive(key)?.content?.toDouble()

internal fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.content?.toBooleanStrict()

internal fun JsonObject.instant(key: String): Instant? = string(key)?.let(Instant::parse)
