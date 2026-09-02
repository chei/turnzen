package dev.turnzen.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RulesTest {
    private fun Position.play(
        color: StoneColor,
        col: Int,
        row: Int,
    ): Position = (Rules.play(this, color, Move.Play(Point(col, row))) as PlayResult.Legal).position

    @Test
    fun `placing a stone alternates the turn`() {
        val p = Position.empty(9).play(StoneColor.BLACK, 4, 4)
        assertEquals(StoneColor.WHITE, p.toMove)
        assertEquals(StoneColor.BLACK, p.board[Point(4, 4)])
        assertEquals(ColoredMove(StoneColor.BLACK, Move.Play(Point(4, 4))), p.lastMove)
    }

    @Test
    fun `occupied and off-board points are illegal`() {
        val p = Position.empty(9).play(StoneColor.BLACK, 4, 4)
        val occupied = Rules.play(p, StoneColor.WHITE, Move.Play(Point(4, 4)))
        assertEquals(PlayResult.Illegal(IllegalMoveReason.OCCUPIED), occupied)
        val off = Rules.play(p, StoneColor.WHITE, Move.Play(Point(9, 0)))
        assertEquals(PlayResult.Illegal(IllegalMoveReason.OFF_BOARD), off)
    }

    @Test
    fun `a single stone is captured when its last liberty is taken`() {
        var p = Position.empty(9)
        p = p.play(StoneColor.WHITE, 4, 4)
        p = p.play(StoneColor.BLACK, 3, 4)
        p = p.play(StoneColor.BLACK, 5, 4)
        p = p.play(StoneColor.BLACK, 4, 3)
        val result = Rules.play(p, StoneColor.BLACK, Move.Play(Point(4, 5)))
        assertIs<PlayResult.Legal>(result)
        assertEquals(setOf(Point(4, 4)), result.captured)
        assertNull(result.position.board[Point(4, 4)])
        assertEquals(1, result.position.prisonersOf(StoneColor.BLACK))
        assertEquals(0, result.position.prisonersOf(StoneColor.WHITE))
    }

    @Test
    fun `a corner group of two is captured`() {
        var p = Position.empty(9)
        p = p.play(StoneColor.WHITE, 0, 0)
        p = p.play(StoneColor.WHITE, 1, 0)
        p = p.play(StoneColor.BLACK, 0, 1)
        p = p.play(StoneColor.BLACK, 1, 1)
        val result = Rules.play(p, StoneColor.BLACK, Move.Play(Point(2, 0)))
        assertIs<PlayResult.Legal>(result)
        assertEquals(setOf(Point(0, 0), Point(1, 0)), result.captured)
        assertEquals(2, result.position.prisonersOf(StoneColor.BLACK))
    }

    @Test
    fun `suicide is illegal`() {
        var p = Position.empty(9)
        p = p.play(StoneColor.WHITE, 1, 0)
        p = p.play(StoneColor.WHITE, 0, 1)
        val result = Rules.play(p, StoneColor.BLACK, Move.Play(Point(0, 0)))
        assertEquals(PlayResult.Illegal(IllegalMoveReason.SUICIDE), result)
    }

    @Test
    fun `filling the last liberty is legal when it captures`() {
        var p = Position.empty(9)
        // White stones surrounding (0,0) but the white stone at (1,0) is itself in atari.
        p = p.play(StoneColor.WHITE, 1, 0)
        p = p.play(StoneColor.WHITE, 0, 1)
        p = p.play(StoneColor.BLACK, 2, 0)
        p = p.play(StoneColor.BLACK, 1, 1)
        val result = Rules.play(p, StoneColor.BLACK, Move.Play(Point(0, 0)))
        assertIs<PlayResult.Legal>(result)
        assertEquals(setOf(Point(1, 0)), result.captured)
    }

    @Test
    fun `simple ko forbids the immediate recapture`() {
        var p = Position.empty(9)
        //  . X O .
        //  X . X O     row 1: black at (0,1), white at (2,1)... build the classic shape:
        p = p.play(StoneColor.BLACK, 1, 0)
        p = p.play(StoneColor.WHITE, 2, 0)
        p = p.play(StoneColor.BLACK, 0, 1)
        p = p.play(StoneColor.WHITE, 3, 1)
        p = p.play(StoneColor.BLACK, 1, 2)
        p = p.play(StoneColor.WHITE, 2, 2)
        p = p.play(StoneColor.BLACK, 2, 1) // black stone at (2,1) with one liberty at (1,1)
        // White captures at (1,1)
        val capture = Rules.play(p, StoneColor.WHITE, Move.Play(Point(1, 1)))
        assertIs<PlayResult.Legal>(capture)
        assertEquals(setOf(Point(2, 1)), capture.captured)
        assertEquals(Point(2, 1), capture.position.koPoint)
        // Black may not retake immediately
        val retake = Rules.play(capture.position, StoneColor.BLACK, Move.Play(Point(2, 1)))
        assertEquals(PlayResult.Illegal(IllegalMoveReason.KO), retake)
        // After a move elsewhere the ko point clears
        val elsewhere =
            (
                Rules.play(
                    capture.position,
                    StoneColor.BLACK,
                    Move.Play(Point(8, 8)),
                ) as PlayResult.Legal
            ).position
        assertNull(elsewhere.koPoint)
    }

    @Test
    fun `pass keeps the board and switches the turn`() {
        val p = Position.empty(9).play(StoneColor.BLACK, 4, 4)
        val result = Rules.play(p, StoneColor.WHITE, Move.Pass)
        assertIs<PlayResult.Legal>(result)
        assertEquals(p.board, result.position.board)
        assertEquals(StoneColor.BLACK, result.position.toMove)
        assertEquals(ColoredMove(StoneColor.WHITE, Move.Pass), result.position.lastMove)
    }

    @Test
    fun `force applies recorded moves without ko or suicide checks`() {
        var p = Position.empty(9)
        p = p.play(StoneColor.WHITE, 1, 0)
        p = p.play(StoneColor.WHITE, 0, 1)
        // A suicide move is applied and the stone disappears again.
        val forced = Rules.force(p, ColoredMove(StoneColor.BLACK, Move.Play(Point(0, 0))))
        assertNull(forced.board[Point(0, 0)])
        assertEquals(StoneColor.WHITE, forced.toMove)
    }
}
