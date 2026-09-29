package dev.turnzen.game.sgf

import dev.turnzen.game.Point

/** SGF point encoding: two lowercase letters, "aa" is the top-left corner. */
object SgfCoordinates {
    fun encode(point: Point): String {
        require(point.col < 26 && point.row < 26) { "$point cannot be encoded in SGF" }
        return "${'a' + point.col}${'a' + point.row}"
    }

    /**
     * Decodes a move value. Returns null for a pass: an empty value, or "tt" on boards
     * up to 19x19 (the FF[3] pass convention that DGS and many tools still emit).
     */
    fun decodeMove(
        value: String,
        boardSize: Int,
    ): Point? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (v == "tt" && boardSize <= 19) return null
        return decodePoint(v)
    }

    fun decodePoint(value: String): Point {
        val v = value.trim()
        require(v.length == 2 && v.all { it in 'a'..'z' }) { "Invalid SGF point '$value'" }
        return Point(v[0] - 'a', v[1] - 'a')
    }

    /** Expands a single point or a compressed rectangle ("aa:cc") into points. */
    fun decodePointList(value: String): List<Point> {
        val v = value.trim()
        val colon = v.indexOf(':')
        if (colon < 0) return listOf(decodePoint(v))
        val from = decodePoint(v.substring(0, colon))
        val to = decodePoint(v.substring(colon + 1))
        return (minOf(from.col, to.col)..maxOf(from.col, to.col)).flatMap { c ->
            (minOf(from.row, to.row)..maxOf(from.row, to.row)).map { r -> Point(c, r) }
        }
    }
}
