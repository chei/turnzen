package dev.turnzen.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrialVariationTest {
    private fun TrialVariation.placed(
        col: Int,
        row: Int,
    ): TrialVariation = (place(Point(col, row)) as TrialOutcome.Placed).variation

    @Test
    fun `stones alternate starting with the first color`() {
        val v = TrialVariation.start(Position.empty(9), StoneColor.WHITE).placed(2, 2).placed(3, 3)
        assertEquals(listOf(StoneColor.WHITE, StoneColor.BLACK), v.moves.map { it.color })
        assertEquals(StoneColor.WHITE, v.nextColor)
        assertEquals(2, v.size)
    }

    @Test
    fun `first move is the move to send`() {
        val v = TrialVariation.start(Position.empty(19)).placed(14, 3).placed(3, 3)
        assertEquals(ColoredMove(StoneColor.BLACK, Move.Play(Point(14, 3))), v.firstMove)
    }

    @Test
    fun `undo removes the last stone and reset removes all`() {
        val v = TrialVariation.start(Position.empty(9)).placed(1, 1).placed(2, 2)
        val undone = v.undo()
        assertEquals(1, undone.size)
        assertNull(undone.position.board[Point(2, 2)])
        assertEquals(StoneColor.WHITE, undone.nextColor)
        val reset = v.reset()
        assertTrue(reset.isEmpty)
        assertEquals(reset.base, reset.position)
        assertEquals(
            v.base,
            v
                .undo()
                .undo()
                .undo()
                .position,
        )
    }

    @Test
    fun `illegal trial stones are rejected and the variation is unchanged`() {
        val v = TrialVariation.start(Position.empty(9)).placed(4, 4)
        val outcome = v.place(Point(4, 4))
        assertIs<TrialOutcome.Rejected>(outcome)
        assertEquals(IllegalMoveReason.OCCUPIED, outcome.reason)
        assertEquals(1, v.size)
    }

    @Test
    fun `captures happen inside the variation`() {
        var base = Position.empty(9)
        base = (Rules.play(base, StoneColor.WHITE, Move.Play(Point(0, 0))) as PlayResult.Legal).position
        base = (Rules.play(base, StoneColor.BLACK, Move.Play(Point(1, 0))) as PlayResult.Legal).position
        val v = TrialVariation.start(base, StoneColor.BLACK).placed(0, 1)
        assertNull(v.position.board[Point(0, 0)])
        assertEquals(1, v.position.prisonersOf(StoneColor.BLACK))
    }

    @Test
    fun `the move to send stays the first stone however the line changes`() {
        // The one rule the whole trial model exists to serve: only the first stone is ever
        // sent, no matter how far the user reads ahead or how they re-work the line.
        val first = ColoredMove(StoneColor.BLACK, Move.Play(Point(14, 3)))
        val deep =
            TrialVariation
                .start(Position.empty(19))
                .placed(14, 3)
                .placed(3, 3)
                .placed(15, 15)
                .placed(2, 16)
        assertEquals(first, deep.firstMove)
        assertEquals(first, deep.undo().firstMove)
        assertEquals(first, deep.truncatedTo(1).firstMove)
        assertNull(deep.reset().firstMove, "nothing to send once the line is cleared")
        assertNull(TrialVariation.start(Position.empty(19)).firstMove)
    }

    @Test
    fun `a pass inside a variation hands the turn over without touching the board`() {
        val v = TrialVariation.start(Position.empty(9)).placed(4, 4)
        val passed = v.pass()
        assertEquals(2, passed.size)
        assertEquals(ColoredMove(StoneColor.WHITE, Move.Pass), passed.moves.last())
        assertEquals(v.position.board, passed.position.board)
        assertEquals(StoneColor.BLACK, passed.nextColor)
        // A pass is still just a trial move: it is not what gets sent.
        assertEquals(ColoredMove(StoneColor.BLACK, Move.Play(Point(4, 4))), passed.firstMove)
    }

    @Test
    fun `positions line up one per move, starting at the server position`() {
        val v = TrialVariation.start(Position.empty(9)).placed(1, 1).placed(2, 2)
        assertEquals(3, v.positions.size, "the base plus one per trial move")
        assertEquals(v.base, v.positions.first())
        assertEquals(v.position, v.positions.last())
        assertEquals(v.base, v.positionAt(0))
        assertNull(v.positionAt(1).board[Point(2, 2)], "one move in, the second stone is not there yet")
        assertEquals(v.position, v.positionAt(2))
    }

    @Test
    fun `a depth outside the variation is clamped rather than throwing`() {
        // The navigation buttons walk this index; running off either end must be harmless.
        val v = TrialVariation.start(Position.empty(9)).placed(1, 1).placed(2, 2)
        assertEquals(v.base, v.positionAt(-5))
        assertEquals(v.position, v.positionAt(99))
        assertTrue(v.truncatedTo(-1).isEmpty)
        assertEquals(2, v.truncatedTo(99).size)
    }

    @Test
    fun `truncating branches a new line from the chosen depth`() {
        val v =
            TrialVariation
                .start(Position.empty(9))
                .placed(1, 1)
                .placed(2, 2)
                .placed(3, 3)
        val branched = v.truncatedTo(1).placed(5, 5)

        assertEquals(2, branched.size)
        assertEquals(listOf(Point(1, 1), Point(5, 5)), branched.moves.map { (it.move as Move.Play).point })
        assertEquals(StoneColor.WHITE, branched.moves.last().color, "colors keep alternating down the new line")
        assertEquals(3, v.size, "the original line is untouched")
    }
}
