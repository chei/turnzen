package dev.turnzen.ogs

import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.CommentVisibility
import dev.turnzen.goserver.GameComment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Game chat, as the realtime server sends it: one `game/<id>/chat` event per line, each
 * `{"channel": …, "line": {"chat_id", "body", "date", "move_number", "player_id", "username"}}`.
 * The same events replay a game's history when a connection joins it with chat on.
 *
 * Channels, and what this client makes of them:
 * - `main` — the players' chat, which observers read too: [CommentVisibility.EVERYONE].
 * - `malkovich` — the "Malkovich log", kept from the opponent until the game is over and
 *   then shown to all: [CommentVisibility.HIDDEN_UNTIL_END].
 * - `personal` — a note the writer keeps for themselves: [CommentVisibility.WRITER_ONLY].
 * - `spectator` and the moderation channels are other people's conversation and are left out,
 *   as is anything written by someone who is not one of the two players.
 *
 * A body is usually plain text. It can also be an object: a message the server translates
 * (`type` "translated", read in English), or a shared variation or review link (`analysis`,
 * `review`), which has no text to show and is skipped.
 */
internal object OgsChat {
    fun comments(
        events: Collection<JsonObject>,
        gamedata: JsonObject,
        moveCount: Int,
    ): List<GameComment> {
        val players = gamedata.obj("players")
        val blackId = gamedata.long("black_player_id") ?: players?.obj("black")?.long("id")
        val whiteId = gamedata.long("white_player_id") ?: players?.obj("white")?.long("id")
        return events
            .mapNotNull { event ->
                val line = event.obj("line") ?: return@mapNotNull null
                val visibility =
                    when (event.str("channel") ?: line.str("channel")) {
                        "main" -> CommentVisibility.EVERYONE
                        "malkovich" -> CommentVisibility.HIDDEN_UNTIL_END
                        "personal" -> CommentVisibility.WRITER_ONLY
                        else -> return@mapNotNull null
                    }
                val by =
                    when (line.long("player_id")) {
                        null -> return@mapNotNull null
                        blackId -> StoneColor.BLACK
                        whiteId -> StoneColor.WHITE
                        else -> return@mapNotNull null
                    }
                val text = text(line)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // The move the writer was looking at; a board they had rewound says an earlier
                // number, which is still where they meant it.
                val afterMove = (line.long("move_number") ?: moveCount.toLong()).coerceIn(0, moveCount.toLong())
                Triple(
                    line.long("date") ?: 0L,
                    line.str("chat_id"),
                    GameComment(afterMove.toInt(), by, text, visibility),
                )
            }.distinctBy { (date, id, comment) -> id ?: "$date/${comment.by}/${comment.text}" }
            .sortedWith(compareBy({ it.third.afterMove }, { it.first }))
            .map { it.third }
    }

    private fun text(line: JsonObject): String? {
        val body = line["body"] ?: return null
        if (body is JsonPrimitive) return if (body.isString) body.content else null
        val obj = body as? JsonObject ?: return null
        return if (obj.str("type") == "translated") obj.str("en") else null
    }
}
