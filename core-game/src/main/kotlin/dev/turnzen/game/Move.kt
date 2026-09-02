package dev.turnzen.game

sealed interface Move {
    data class Play(
        val point: Point,
    ) : Move

    data object Pass : Move
}

data class ColoredMove(
    val color: StoneColor,
    val move: Move,
) {
    val point: Point?
        get() = (move as? Move.Play)?.point
}
