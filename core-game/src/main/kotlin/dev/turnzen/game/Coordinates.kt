package dev.turnzen.game

/** Human-readable board labels such as "D4" or "O15". Column letters skip "I". */
object Coordinates {
    private const val LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"

    fun columnLetter(col: Int): Char {
        require(col in LETTERS.indices) { "No column letter for column $col" }
        return LETTERS[col]
    }

    fun label(
        point: Point,
        size: Int,
    ): String {
        require(point.col < size && point.row < size) { "$point is outside a ${size}x$size board" }
        return "${columnLetter(point.col)}${size - point.row}"
    }

    /** Parses "d4", "O15", ... Returns null for anything that is not a point on the board. */
    fun parse(
        label: String,
        size: Int,
    ): Point? {
        val trimmed = label.trim().uppercase()
        if (trimmed.length < 2) return null
        val col = LETTERS.indexOf(trimmed[0])
        val rowNumber = trimmed.substring(1).toIntOrNull() ?: return null
        if (col !in 0 until size || rowNumber !in 1..size) return null
        return Point(col, size - rowNumber)
    }
}
