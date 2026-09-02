package dev.turnzen.game

/**
 * A board intersection. 0-based, `col` grows to the right and `row` grows downwards
 * (row 0 is the top line). Conversion to "O15"-style labels or SGF letters happens only
 * in [Coordinates] and the SGF layer.
 */
data class Point(
    val col: Int,
    val row: Int,
) {
    init {
        require(col >= 0 && row >= 0) { "Point coordinates must be non-negative: ($col, $row)" }
    }
}
