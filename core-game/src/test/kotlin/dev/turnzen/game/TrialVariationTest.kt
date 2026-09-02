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
}
