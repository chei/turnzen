package dev.turnzen.game

object BoardSizes {
    const val MIN = 5
    const val MAX = 25

    val standard: List<Int> = listOf(9, 13, 19)

    fun requireValid(size: Int) {
        require(size in MIN..MAX) { "Unsupported board size $size (allowed $MIN..$MAX)" }
    }

    /** Star points. 19: lines 4/10/16, 13: 4/7/10, 9: 3/5/7; derived for other sizes. */
    fun hoshi(size: Int): List<Point> {
        requireValid(size)
        if (size < 7) return emptyList()
        val edge = if (size < 13) 2 else 3
        val lines = linkedSetOf(edge, size - 1 - edge)
        if (size % 2 == 1) lines += size / 2
        val sorted = lines.sorted()
        return sorted.flatMap { c -> sorted.map { r -> Point(c, r) } }
    }
}
