package dev.turnzen.game

enum class IllegalMoveReason {
    OFF_BOARD,
    OCCUPIED,
    SUICIDE,
    KO,
}

sealed interface PlayResult {
    data class Legal(
        val position: Position,
        val captured: Set<Point>,
    ) : PlayResult

    data class Illegal(
        val reason: IllegalMoveReason,
    ) : PlayResult
}

/**
 * Go rules needed to evaluate a position: captures, suicide and simple ko. Scoring and
 * superko are out of scope for now.
 */
object Rules {
    fun play(
        position: Position,
        move: ColoredMove,
    ): PlayResult = play(position, move.color, move.move)

    fun play(
        position: Position,
        color: StoneColor,
        move: Move,
    ): PlayResult =
        when (move) {
            Move.Pass -> {
                PlayResult.Legal(
                    position.copy(toMove = color.opposite, koPoint = null, lastMove = ColoredMove(color, move)),
                    emptySet(),
                )
            }

            is Move.Play -> {
                place(position, color, move.point, strict = true)
            }
        }

    /**
     * Applies a move that is known to have been accepted elsewhere (a server record).
     * Captures are still performed, but suicide and ko are not rejected.
     */
    fun force(
        position: Position,
        move: ColoredMove,
    ): Position =
        when (val m = move.move) {
            Move.Pass -> {
                (play(position, move.color, m) as PlayResult.Legal).position
            }

            is Move.Play -> {
                when (val result = place(position, move.color, m.point, strict = false)) {
                    is PlayResult.Legal -> result.position
                    is PlayResult.Illegal -> throw IllegalArgumentException("Cannot force move $move: ${result.reason}")
                }
            }
        }

    private fun place(
        position: Position,
        color: StoneColor,
        point: Point,
        strict: Boolean,
    ): PlayResult {
        val board = position.board
        if (point !in board) return PlayResult.Illegal(IllegalMoveReason.OFF_BOARD)
        if (board[point] != null) return PlayResult.Illegal(IllegalMoveReason.OCCUPIED)
        if (strict && point == position.koPoint) return PlayResult.Illegal(IllegalMoveReason.KO)

        var next = board.with(point, color)
        val captured = LinkedHashSet<Point>()
        for (n in next.neighbors(point)) {
            if (next[n] == color.opposite) {
                val group = next.group(n)
                if (next.liberties(group).isEmpty()) captured += group
            }
        }
        if (captured.isNotEmpty()) next = next.without(captured)

        val ownGroup = next.group(point)
        if (next.liberties(ownGroup).isEmpty()) {
            if (strict) return PlayResult.Illegal(IllegalMoveReason.SUICIDE)
            next = next.without(ownGroup)
        }

        val koPoint =
            if (captured.size == 1 && ownGroup.size == 1 && next.liberties(ownGroup).size == 1) {
                captured.first()
            } else {
                null
            }

        val prisoners = position.prisoners.toMutableMap()
        prisoners[color] = (prisoners[color] ?: 0) + captured.size

        return PlayResult.Legal(
            Position(
                board = next,
                toMove = color.opposite,
                koPoint = koPoint,
                prisoners = prisoners,
                lastMove = ColoredMove(color, Move.Play(point)),
            ),
            captured,
        )
    }
}
