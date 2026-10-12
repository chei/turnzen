package dev.turnzen.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeadStonesTest {
    /** Rows top to bottom: 'X' black, 'O' white, '.' empty. */
    private fun board(vararg rows: String): Board {
        var board = Board.empty(rows.size)
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, c ->
                when (c) {
                    'X' -> board = board.with(Point(col, row), StoneColor.BLACK)
                    'O' -> board = board.with(Point(col, row), StoneColor.WHITE)
                }
            }
        }
        return board
    }

    private fun at(
        label: String,
        size: Int,
    ): Point = checkNotNull(Coordinates.parse(label, size))

    private fun points(
        size: Int,
        vararg labels: String,
    ): Set<Point> = labels.map { at(it, size) }.toSet()

    /**
     * A finished 13x13 game, black to count, where white has two dead corners on the right:
     * six lone stones in the top right and a chain in the bottom right. Marking them one chain
     * at a time took seven taps.
     */
    private val finished =
        board(
            "......OXX..X.",
            ".....O.OX.O.O",
            "...O..OOXX.O.",
            ".....OOOOXXXO",
            "....OXXXOOXO.",
            "..O..OOXXX.XO",
            ".OO..O.OX.XXX",
            "OOXOO.OOOX.XO",
            "OXXO.OXXX.XOO",
            "X.XOOX...XXXO",
            ".XXOXXX.XOOOO",
            "...XXXX..X...",
            ".......XXX.X.",
        )

    private val topRight = points(13, "L12", "N12", "M11", "N10", "M9", "N8")
    private val bottomRight = points(13, "K3", "L3", "M3", "N3", "N4", "N5", "M5", "N6")

    @Test
    fun `one tap marks every stone of that colour in the area`() {
        assertEquals(topRight, DeadStones.toggle(finished, emptySet(), at("M11", 13)))
        assertEquals(bottomRight, DeadStones.toggle(finished, emptySet(), at("N3", 13)))
    }

    @Test
    fun `tapping a dead area again brings all of it back`() {
        val dead = DeadStones.toggle(finished, emptySet(), at("M11", 13))
        assertEquals(emptySet(), DeadStones.toggle(finished, dead, at("N8", 13)))
    }

    @Test
    fun `a tap on an empty point changes nothing`() {
        val dead = DeadStones.toggle(finished, emptySet(), at("M11", 13))
        assertEquals(dead, DeadStones.toggle(finished, dead, at("A13", 13)))
    }

    @Test
    fun `the area stops at stones of the other state`() {
        // Two white stones in black's area, linked only by empty points. Once one is revived on
        // its own terms the other stays as it was: the area follows the stones' state.
        val b =
            board(
                ".....",
                ".O.O.",
                ".....",
                "XXXXX",
                ".....",
            )
        val both = DeadStones.toggle(b, emptySet(), Point(1, 1))
        assertEquals(setOf(Point(1, 1), Point(3, 1)), both)
        val oneDeadAlready = setOf(Point(3, 1))
        assertEquals(setOf(Point(1, 1), Point(3, 1)), DeadStones.toggle(b, oneDeadAlready, Point(1, 1)))
    }

    @Test
    fun `killing an area revives the dead stones of the other colour around it`() {
        val b =
            board(
                "..X..",
                "OOOOO",
                ".....",
                ".....",
                ".....",
            )
        // Black marked the white wall dead; now the black stone in front of it is marked dead
        // instead, so the wall cannot be.
        val wallDead = DeadStones.toggle(b, emptySet(), Point(0, 1))
        assertEquals(5, wallDead.size)
        assertEquals(setOf(Point(2, 0)), DeadStones.toggle(b, wallDead, Point(2, 0)))
    }

    @Test
    fun `the guess finds both dead corners and nothing else`() {
        val guess = DeadStones.guess(Position(finished, StoneColor.BLACK))
        assertEquals(topRight + bottomRight, guess)
    }

    @Test
    fun `the guess is two taps, and the taps give the guess`() {
        val position = Position(finished, StoneColor.BLACK)
        val taps = DeadStones.guessTaps(position)
        assertEquals(2, taps.size)
        val dead = taps.fold(emptySet<Point>()) { dead, tap -> DeadStones.toggle(finished, dead, tap) }
        assertEquals(topRight + bottomRight, dead)
    }

    @Test
    fun `living groups and a seki are left alone`() {
        // Black and white each have two eyes, and share two liberties in the middle with a
        // small seki pair: nothing here is dead.
        val b =
            board(
                ".X.XOO.O.",
                "XXXXO.OOO",
                "XXXXOOOOO",
                "XXXXOOOOO",
                "XXXX.OOOO",
                "XXXXOOOOO",
                "XXXXOOOOO",
                "X.XXO.O.O",
                ".XXXOOOOO",
            )
        assertTrue(DeadStones.guess(Position(b, StoneColor.BLACK)).isEmpty())
    }

    @Test
    fun `stones with room inside the opponent's territory are still guessed dead`() {
        // Too much room for random play to settle: the second look at what walls them in does.
        val b =
            board(
                ".........",
                "...O.....",
                ".........",
                ".........",
                "XXXXXXXXX",
                "OOOOOOOOO",
                ".........",
                "......X..",
                ".........",
            )
        assertEquals(setOf(Point(3, 1), Point(6, 7)), DeadStones.guess(Position(b, StoneColor.BLACK)))
    }

    @Test
    fun `an empty territory does not make its wall look dead`() {
        val b =
            board(
                ".........",
                ".........",
                "...O.....",
                ".........",
                ".........",
                "XXXXXXXXX",
                "OOOOOOOOO",
                ".........",
                ".........",
            )
        assertEquals(setOf(Point(3, 2)), DeadStones.guess(Position(b, StoneColor.BLACK)))
    }

    @Test
    fun `the same position always gets the same guess`() {
        val position = Position(finished, StoneColor.BLACK)
        assertEquals(DeadStones.guessTaps(position), DeadStones.guessTaps(Position(finished, StoneColor.WHITE)))
    }
}
