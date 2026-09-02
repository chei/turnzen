package dev.turnzen.game

/**
 * A board plus the state needed to judge the next move: whose turn it is, the ko point
 * (if any), prisoner counts and the last move played.
 */
data class Position(
    val board: Board,
    val toMove: StoneColor,
    val koPoint: Point? = null,
    val prisoners: Map<StoneColor, Int> = mapOf(StoneColor.BLACK to 0, StoneColor.WHITE to 0),
    val lastMove: ColoredMove? = null,
) {
    val size: Int
        get() = board.size

    /** Stones captured by [color] so far. */
    fun prisonersOf(color: StoneColor): Int = prisoners[color] ?: 0

    companion object {
        fun empty(
            size: Int,
            toMove: StoneColor = StoneColor.BLACK,
        ): Position = Position(Board.empty(size), toMove)
    }
}
