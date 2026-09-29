package dev.turnzen.game

data class PlayerInfo(
    val name: String? = null,
    val rank: String? = null,
)

/**
 * A complete game as recorded by a server or an SGF file: setup, moves and metadata.
 * Server-agnostic; the DGS module produces it from SGF, the UI replays it.
 */
data class GameRecord(
    val boardSize: Int,
    val komi: Double? = null,
    val handicap: Int = 0,
    val setupBlack: List<Point> = emptyList(),
    val setupWhite: List<Point> = emptyList(),
    val moves: List<ColoredMove> = emptyList(),
    val black: PlayerInfo = PlayerInfo(),
    val white: PlayerInfo = PlayerInfo(),
    val result: String? = null,
    /** Explicit first player (SGF `PL`); when null, White starts after handicap stones, else Black. */
    val firstToMove: StoneColor? = null,
) {
    init {
        BoardSizes.requireValid(boardSize)
    }

    val initialPosition: Position
        get() {
            var board = Board.empty(boardSize)
            for (p in setupBlack) board = board.with(p, StoneColor.BLACK)
            for (p in setupWhite) board = board.with(p, StoneColor.WHITE)
            val toMove =
                firstToMove
                    ?: if (setupBlack.isNotEmpty() && setupWhite.isEmpty()) StoneColor.WHITE else StoneColor.BLACK
            return Position(board, toMove)
        }

    /** The color expected to move after all recorded moves. */
    val nextToMove: StoneColor
        get() = moves.lastOrNull()?.color?.opposite ?: initialPosition.toMove
}

/** Positions after 0..n moves of a record, computed once. */
class Replay(
    val record: GameRecord,
) {
    val positions: List<Position> =
        buildList {
            var position = record.initialPosition
            add(position)
            for (move in record.moves) {
                position = Rules.force(position, move)
                add(position)
            }
        }

    val moveCount: Int
        get() = record.moves.size

    fun positionAfter(moveCount: Int): Position = positions[moveCount.coerceIn(0, positions.lastIndex)]

    val finalPosition: Position
        get() = positions.last()
}
