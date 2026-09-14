package dev.turnzen.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    /**
     * Builds a position from an ASCII diagram, rows top to bottom: 'X' black, 'O' white,
     * '.' empty. Lets a test state the board it means rather than a list of moves.
     */
    private fun position(vararg rows: String): Position {
        var board = Board.empty(rows.size)
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, c ->
                when (c) {
                    'X' -> board = board.with(Point(col, row), StoneColor.BLACK)
                    'O' -> board = board.with(Point(col, row), StoneColor.WHITE)
                }
            }
        }
        return Position(board, StoneColor.BLACK)
    }

    @Test
    fun `each side gets the empty points it alone surrounds`() {
        // A 9x9 split down the middle: black owns the left three files, white the right three.
        val count =
            Scoring.count(
                position(
                    "...XO....",
                    "...XO....",
                    "...XO....",
                    "...XO....",
                    "...XO....",
                    "...XO....",
                    "...XO....",
                    "...XO....",
                    "...XO....",
                ),
            )
        assertEquals(27, count.territoryOf(StoneColor.BLACK).size)
        assertEquals(36, count.territoryOf(StoneColor.WHITE).size)
        assertTrue(count.dame.isEmpty())
    }

    @Test
    fun `a region touching both colours is nobody's`() {
        val count =
            Scoring.count(
                position(
                    "X.O..",
                    "X.O..",
                    "X.O..",
                    "X.O..",
                    "X.O..",
                ),
            )
        // The file between the walls is dame; so is everything east of white, which black's
        // wall never reaches.
        assertEquals(5, count.dame.size)
        assertTrue(count.territoryOf(StoneColor.BLACK).isEmpty())
        assertEquals(10, count.territoryOf(StoneColor.WHITE).size)
    }

    @Test
    fun `dead stones are lifted, so their points go to the captor and count as prisoners`() {
        // One white stone sits inside black's enclosure. While it is treated as alive the
        // region touches both colours and is worth nothing to anyone; dead, it is six points
        // of territory and a prisoner besides.
        val board =
            position(
                "XXXXX",
                "X.O.X",
                "X...X",
                "XXXXX",
                "XXXXX",
            )
        val white = Point(2, 1)

        val alive = Scoring.count(board)
        assertTrue(alive.territoryOf(StoneColor.BLACK).isEmpty())
        assertEquals(5, alive.dame.size)
        assertEquals(0, alive.capturesOf(StoneColor.BLACK))

        val dead = Scoring.count(board, dead = setOf(white))
        assertEquals(6, dead.territoryOf(StoneColor.BLACK).size)
        assertTrue(dead.dame.isEmpty())
        assertEquals(1, dead.capturesOf(StoneColor.BLACK))
    }

    @Test
    fun `territory scoring counts captures and komi, area scoring counts stones`() {
        // Two walled-off houses of seven points each, and four stones black took in play.
        val position =
            position(
                "XXXXXXXXX",
                "X.......X",
                "XXXXXXXXX",
                "XXXXXXXXX",
                "OOOOOOOOO",
                "OOOOOOOOO",
                "O.......O",
                "OOOOOOOOO",
                "OOOOOOOOO",
            ).let { it.copy(prisoners = mapOf(StoneColor.BLACK to 4, StoneColor.WHITE to 0)) }

        val japanese = Scoring.count(position, komi = 6.5, rules = ScoringRules.TERRITORY)
        // Black: 7 points of territory plus 4 prisoners. White: 7 points plus komi.
        assertEquals(11.0, japanese.blackPoints)
        assertEquals(13.5, japanese.whitePoints)
        assertEquals(StoneColor.WHITE, japanese.result.winner)
        assertEquals(2.5, japanese.result.margin)

        val chinese = Scoring.count(position, komi = 6.5, rules = ScoringRules.AREA)
        // The same board by area: 29 black stones and 38 white ones join their territory,
        // and the prisoners stop counting.
        assertEquals(36.0, chinese.blackPoints)
        assertEquals(51.5, chinese.whitePoints)
    }

    @Test
    fun `an equal count is a draw`() {
        val result = GameResult.fromPoints(10.0, 10.0)
        assertEquals(null, result.winner)
        assertEquals(ResultReason.JIGO, result.by)
    }

    @Test
    fun `an empty board is nobody's territory`() {
        val count = Scoring.count(Position.empty(9))
        assertEquals(81, count.dame.size)
        assertEquals(0.0, count.blackPoints)
    }
}
