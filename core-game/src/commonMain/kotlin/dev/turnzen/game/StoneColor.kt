package dev.turnzen.game

enum class StoneColor {
    BLACK,
    WHITE,
    ;

    val opposite: StoneColor
        get() = if (this == BLACK) WHITE else BLACK
}
