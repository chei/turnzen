package dev.turnzen.dgs

import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfGame
import dev.turnzen.goserver.CommentVisibility
import dev.turnzen.goserver.GameComment

/**
 * Reads the players' move messages out of a DGS SGF download.
 *
 * What DGS writes there (read from the server's SGF builder, September 2026):
 *
 * - A message is the `C` of the node of the move it was sent with. In a two-player game the
 *   text is bare — no name in front — so the move's colour is the only author mark. The
 *   message sent with the last handicap stone lands on the root node, next to `AB`.
 * - Parts of a message can be marked up for a different audience: `<c>…</c>` (long form
 *   `<comment>`) is public, `<h>…</h>` (`<hidden>`) stays with its writer until the game ends
 *   and is then public, and `<m>…</m>` (`<mysecret>`) stays with its writer until the game
 *   ends and then goes to the opponent only. Everything outside the tags is for the two
 *   players. The server has already dropped whatever this viewer may not read; what is left
 *   keeps its tags, which is how the writer sees which of their own parts are still private.
 * - With `owned_comments=1` the viewer's private game notes are appended to the comment of
 *   the last move of a running game — or of the result node of a finished one — as a block
 *   headed `Notes - <name as in PB/PW>:`. They are not a message and are cut off here.
 * - A finished game ends in an extra node, a pass in form, whose comment is the server's
 *   own: the scoring breakdown and a `Result:` line. Not a message either.
 */
internal object DgsComments {
    private val tagged =
        Regex(
            "<(c|h|m)(?:omment|idden|ysecret)? *>(.*?)</\\1[a-z]* *>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    fun parse(
        sgf: SgfGame,
        /** The viewer's colour, for finding their notes; null when they are not a player. */
        myColor: StoneColor?,
    ): List<GameComment> {
        val moves = sgf.record.moves
        val finished = sgf.rootProperties["RE"].orEmpty().any { it.isNotBlank() }
        val myName =
            when (myColor) {
                StoneColor.BLACK -> sgf.rootProperties["PB"]?.firstOrNull()
                StoneColor.WHITE -> sgf.rootProperties["PW"]?.firstOrNull()
                null -> null
            }
        return sgf.comments.entries
            .sortedBy { it.key }
            .flatMap { (afterMove, raw) ->
                val text = withoutNotes(raw, myName)
                if (finished && afterMove == moves.size && isResult(text)) return@flatMap emptyList()
                val by =
                    when {
                        afterMove > 0 -> moves.getOrNull(afterMove - 1)?.color

                        // Only a handicap game's root carries a player's words: black's, sent
                        // with the setup. Anything else there is the server describing the game.
                        sgf.record.handicap > 0 -> StoneColor.BLACK

                        else -> null
                    } ?: return@flatMap emptyList()
                parts(text).map { (visibility, part) -> GameComment(afterMove, by, part, visibility) }
            }
    }

    /** The message split into its audiences, in the order written. */
    internal fun parts(text: String): List<Pair<CommentVisibility, String>> {
        val parts = ArrayList<Pair<CommentVisibility, String>>()

        fun add(
            visibility: CommentVisibility,
            part: String,
        ) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) parts += visibility to trimmed
        }
        var at = 0
        for (match in tagged.findAll(text)) {
            add(CommentVisibility.PLAYERS, text.substring(at, match.range.first))
            val visibility =
                when (match.groupValues[1].lowercase()) {
                    "c" -> CommentVisibility.EVERYONE
                    "h" -> CommentVisibility.HIDDEN_UNTIL_END
                    else -> CommentVisibility.SECRET_UNTIL_END
                }
            add(visibility, match.groupValues[2])
            at = match.range.last + 1
        }
        add(CommentVisibility.PLAYERS, text.substring(at))
        return parts
    }

    private fun withoutNotes(
        text: String,
        myName: String?,
    ): String {
        if (myName.isNullOrBlank()) return text
        val header = "Notes - $myName:\n"
        val start =
            when {
                text.startsWith(header) -> 0
                else -> text.lastIndexOf("\n\n$header").takeIf { it >= 0 } ?: return text
            }
        return text.substring(0, start)
    }

    private fun isResult(text: String): Boolean = text.lineSequence().any { it.startsWith("Result: ") }
}
