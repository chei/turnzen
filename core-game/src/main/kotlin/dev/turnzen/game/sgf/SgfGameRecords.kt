package dev.turnzen.game.sgf

import dev.turnzen.game.ColoredMove
import dev.turnzen.game.GameRecord
import dev.turnzen.game.Move
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.StoneColor

/** A parsed game: the record plus the raw root properties for server-specific extras. */
data class SgfGame(
    val record: GameRecord,
    val rootProperties: Map<String, List<String>>,
    /**
     * The main line's comments (`C`), keyed by how many of [GameRecord.moves] had been played
     * at the node carrying them: 0 for the root and any setup nodes before the first move, 1
     * for the node of the first move, and so on. Verbatim — what a comment *means* (who wrote
     * it, whether part of it is private) is the server's convention, not SGF's. Several
     * comments landing on one key (setup nodes) are joined by a blank line.
     */
    val comments: Map<Int, String> = emptyMap(),
)

/** Converts between [GameRecord] and SGF text. Only the main line is read. */
object SgfGameRecords {
    fun read(text: String): SgfGame = fromTree(SgfParser.parseFirst(text))

    fun fromTree(tree: SgfGameTree): SgfGame {
        val nodes = tree.mainLine
        val root = nodes.first()
        val size = parseSize(root.first("SZ"))
        val setupBlack = ArrayList<dev.turnzen.game.Point>()
        val setupWhite = ArrayList<dev.turnzen.game.Point>()
        val moves = ArrayList<ColoredMove>()
        var firstToMove: StoneColor? = root.first("PL")?.let { parseColor(it) }
        val comments = LinkedHashMap<Int, String>()

        for (node in nodes) {
            if (moves.isEmpty()) {
                node.values("AB").forEach { setupBlack += SgfCoordinates.decodePointList(it) }
                node.values("AW").forEach { setupWhite += SgfCoordinates.decodePointList(it) }
                node.first("PL")?.let { firstToMove = parseColor(it) }
            }
            node.first("B")?.let { moves += ColoredMove(StoneColor.BLACK, toMove(it, size)) }
            node.first("W")?.let { moves += ColoredMove(StoneColor.WHITE, toMove(it, size)) }
            node.first("C")?.takeIf { it.isNotBlank() }?.let { comment ->
                comments.merge(moves.size, comment) { earlier, later -> "$earlier\n\n$later" }
            }
        }

        val record =
            GameRecord(
                boardSize = size,
                komi = root.first("KM")?.trim()?.toDoubleOrNull(),
                handicap = root.first("HA")?.trim()?.toIntOrNull() ?: 0,
                setupBlack = setupBlack,
                setupWhite = setupWhite,
                moves = moves,
                black = PlayerInfo(root.first("PB"), root.first("BR")),
                white = PlayerInfo(root.first("PW"), root.first("WR")),
                result = root.first("RE"),
                firstToMove = firstToMove,
            )
        return SgfGame(record, root.properties, comments)
    }

    fun write(record: GameRecord): String =
        buildString {
            append("(;FF[4]GM[1]CA[UTF-8]AP[Turnzen]")
            append("SZ[").append(record.boardSize).append(']')
            record.komi?.let { append("KM[").append(formatKomi(it)).append(']') }
            if (record.handicap > 0) append("HA[").append(record.handicap).append(']')
            record.black.name?.let { append("PB[").append(escape(it)).append(']') }
            record.black.rank?.let { append("BR[").append(escape(it)).append(']') }
            record.white.name?.let { append("PW[").append(escape(it)).append(']') }
            record.white.rank?.let { append("WR[").append(escape(it)).append(']') }
            record.result?.let { append("RE[").append(escape(it)).append(']') }
            record.firstToMove?.let { append("PL[").append(if (it == StoneColor.BLACK) 'B' else 'W').append(']') }
            if (record.setupBlack.isNotEmpty()) {
                append("AB")
                record.setupBlack.forEach { append('[').append(SgfCoordinates.encode(it)).append(']') }
            }
            if (record.setupWhite.isNotEmpty()) {
                append("AW")
                record.setupWhite.forEach { append('[').append(SgfCoordinates.encode(it)).append(']') }
            }
            for (move in record.moves) {
                append('\n').append(';')
                append(if (move.color == StoneColor.BLACK) 'B' else 'W')
                append('[')
                when (val m = move.move) {
                    Move.Pass -> Unit
                    is Move.Play -> append(SgfCoordinates.encode(m.point))
                }
                append(']')
            }
            append(")\n")
        }

    private fun parseSize(value: String?): Int {
        val v = value?.trim() ?: return 19
        val first = v.substringBefore(':').toIntOrNull() ?: throw SgfParseException("Invalid SZ '$value'")
        val second =
            v.substringAfter(':', first.toString()).toIntOrNull() ?: throw SgfParseException("Invalid SZ '$value'")
        if (first != second) throw SgfParseException("Non-square boards are not supported: SZ[$v]")
        return first
    }

    private fun parseColor(value: String): StoneColor? =
        when (value.trim().uppercase()) {
            "B" -> StoneColor.BLACK
            "W" -> StoneColor.WHITE
            else -> null
        }

    private fun toMove(
        value: String,
        size: Int,
    ): Move = SgfCoordinates.decodeMove(value, size)?.let { Move.Play(it) } ?: Move.Pass

    private fun formatKomi(komi: Double): String =
        if (komi ==
            komi.toLong().toDouble()
        ) {
            komi.toLong().toString()
        } else {
            komi.toString()
        }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("]", "\\]")
}
