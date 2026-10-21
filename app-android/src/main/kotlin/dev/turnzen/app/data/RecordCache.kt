package dev.turnzen.app.data

import android.content.Context
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.CommentVisibility
import dev.turnzen.goserver.GameComment
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameInfo
import dev.turnzen.goserver.PlayerDetail
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.TournamentRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/** A game's full record as it was downloaded, and when. */
data class StoredRecord(
    val detail: GameDetail,
    val fetchedAt: Instant,
)

/**
 * Game records kept across app restarts, so a game opened once opens again without a
 * download while the list agrees it has not moved on, and opens at all while its server is
 * down. Holds what was downloaded and nothing more: there is no bulk sync (DGS meters
 * requests), so a game never opened on this phone is not here.
 */
interface RecordCache {
    fun read(id: GameId): StoredRecord?

    fun write(record: StoredRecord)

    fun remove(id: GameId)

    /** Drops [server]'s records of every game but [keep]: the games still running there. */
    fun retain(
        server: ServerId,
        keep: Set<GameId>,
    )

    fun forget(server: ServerId)

    fun clear()

    /** No persistence: records live and die with the process. */
    object None : RecordCache {
        override fun read(id: GameId): StoredRecord? = null

        override fun write(record: StoredRecord) = Unit

        override fun remove(id: GameId) = Unit

        override fun retain(
            server: ServerId,
            keep: Set<GameId>,
        ) = Unit

        override fun forget(server: ServerId) = Unit

        override fun clear() = Unit
    }
}

/**
 * [RecordCache] as a file per game under the app's private files directory
 * (`records/<server>/<game>.json`), which other apps cannot read. The moves are kept as SGF
 * inside that file; the rest (the summary, the players' details, the comments) as JSON beside
 * it. Some kilobytes a game.
 *
 * Read and written on the caller's thread: a file this size costs about a millisecond, less
 * than the hop to another dispatcher would be worth. A write goes to a temporary file that is
 * then renamed over the old one, so a crash mid-write leaves the previous copy, never half of
 * one.
 */
class FileRecordCache(
    private val root: File,
) : RecordCache {
    constructor(context: Context) : this(File(context.filesDir, "records"))

    override fun read(id: GameId): StoredRecord? {
        val file = fileFor(id) ?: return null
        val text =
            try {
                file.readText()
            } catch (e: IOException) {
                return null
            }
        return RecordCodec.decode(id.server, text)
    }

    override fun write(record: StoredRecord) {
        val file = fileFor(record.detail.summary.id) ?: return
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(RecordCodec.encode(record))
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: IOException) {
            // A record that was not kept is downloaded again next time.
        }
    }

    override fun remove(id: GameId) {
        fileFor(id)?.delete()
    }

    override fun retain(
        server: ServerId,
        keep: Set<GameId>,
    ) {
        val names = keep.filter { it.server == server }.mapNotNullTo(HashSet()) { fileFor(it)?.name }
        dirFor(server)?.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    override fun forget(server: ServerId) {
        dirFor(server)?.deleteRecursively()
    }

    override fun clear() {
        root.deleteRecursively()
    }

    // Ids become file names, so only ids that are safe as one are kept. Both servers number
    // their games; anything else is simply not stored.
    private fun dirFor(server: ServerId): File? = if (SAFE.matches(server.value)) File(root, server.value) else null

    private fun fileFor(id: GameId): File? =
        if (SAFE.matches(id.value)) {
            dirFor(id.server)?.let {
                File(it, "${id.value}.json")
            }
        } else {
            null
        }

    private companion object {
        val SAFE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * The stored form of [StoredRecord]. Like [GamesCodec], a miss rather than an error for
 * anything that does not read back.
 */
internal object RecordCodec {
    private const val VERSION = 1

    fun encode(record: StoredRecord): String {
        val detail = record.detail
        return buildJsonObject {
            put("v", VERSION)
            put("fetchedAt", record.fetchedAt.toString())
            put("summary", GamesCodec.game(detail.summary))
            put("sgf", SgfGameRecords.write(detail.record))
            detail.info?.let { put("info", info(it)) }
            detail.comments?.let { comments ->
                put(
                    "comments",
                    buildJsonArray {
                        comments.forEach {
                            add(
                                buildJsonObject {
                                    put("afterMove", it.afterMove)
                                    put("by", it.by.name)
                                    put("text", it.text)
                                    put("visibility", it.visibility.name)
                                },
                            )
                        }
                    },
                )
            }
        }.toString()
    }

    fun decode(
        server: ServerId,
        text: String,
    ): StoredRecord? =
        try {
            val root = Json.parseToJsonElement(text) as JsonObject
            if (root.long("v") != VERSION.toLong()) {
                null
            } else {
                StoredRecord(
                    detail =
                        GameDetail(
                            summary = GamesCodec.summary(server, root["summary"] as JsonObject),
                            record = SgfGameRecords.read(root.string("sgf")!!).record,
                            info = (root["info"] as? JsonObject)?.let(::info),
                            comments =
                                (root["comments"] as? JsonArray)?.map {
                                    val o = it as JsonObject
                                    GameComment(
                                        afterMove = o.int("afterMove")!!,
                                        by = StoneColor.valueOf(o.string("by")!!),
                                        text = o.string("text")!!,
                                        visibility = CommentVisibility.valueOf(o.string("visibility")!!),
                                    )
                                },
                        ),
                    fetchedAt = root.instant("fetchedAt")!!,
                )
            }
        } catch (e: RuntimeException) {
            // Malformed JSON or SGF, a missing field, an enum value this build does not know.
            null
        }

    private fun info(info: GameInfo): JsonObject =
        buildJsonObject {
            put("black", player(info.black))
            put("white", player(info.white))
            put("komi", info.komi)
            put("handicap", info.handicap)
            put("ruleset", info.ruleset)
            put("rated", info.rated)
            put("timeLimit", info.timeLimit)
            put("weekendClock", info.weekendClock)
            put("startedAt", info.startedAt?.toString())
            info.tournament?.let { t ->
                put(
                    "tournament",
                    buildJsonObject {
                        put("id", t.id)
                        put("name", t.name)
                        put("kind", t.kind)
                        put("isLadder", t.isLadder)
                        put("round", t.round)
                        put("roundCount", t.roundCount)
                    },
                )
            }
            put("scoring", info.scoring?.name)
        }

    private fun info(o: JsonObject): GameInfo =
        GameInfo(
            black = player(o["black"] as JsonObject),
            white = player(o["white"] as JsonObject),
            komi = o.double("komi"),
            handicap = o.int("handicap")!!,
            ruleset = o.string("ruleset"),
            rated = o.boolean("rated"),
            timeLimit = o.string("timeLimit"),
            weekendClock = o.boolean("weekendClock"),
            startedAt = o.instant("startedAt"),
            tournament =
                (o["tournament"] as? JsonObject)?.let {
                    TournamentRef(
                        id = it.string("id")!!,
                        name = it.string("name"),
                        kind = it.string("kind"),
                        isLadder = it.boolean("isLadder")!!,
                        round = it.int("round"),
                        roundCount = it.int("roundCount"),
                    )
                },
            scoring = o.string("scoring")?.let(ScoringRules::valueOf),
        )

    private fun player(p: PlayerDetail): JsonObject =
        buildJsonObject {
            put("handle", p.handle)
            put("displayName", p.displayName)
            put("rank", p.rank)
            put("country", p.country)
            put("rankAtStart", p.rankAtStart)
            put("prisoners", p.prisoners)
            p.remainingTime?.let { put("remainingTime", GamesCodec.remainingTime(it)) }
            put("ratingChange", p.ratingChange)
        }

    private fun player(o: JsonObject): PlayerDetail =
        PlayerDetail(
            handle = o.string("handle")!!,
            displayName = o.string("displayName"),
            rank = o.string("rank"),
            country = o.string("country"),
            rankAtStart = o.string("rankAtStart"),
            prisoners = o.int("prisoners"),
            remainingTime = (o["remainingTime"] as? JsonObject)?.let(GamesCodec::remainingTime),
            ratingChange = o.double("ratingChange"),
        )
}
