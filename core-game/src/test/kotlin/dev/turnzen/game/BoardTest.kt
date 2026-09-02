package dev.turnzen.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardTest {
    @Test
    fun `empty board has no stones`() {
        val board = Board.empty(9)
        assertTrue(board.stones().isEmpty())
        assertNull(board[Point(4, 4)])
    }

    @Test
    fun `with returns a new board and leaves the original untouched`() {
        val empty = Board.empty(9)
        val withStone = empty.with(Point(2, 3), StoneColor.BLACK)
        assertNull(empty[Point(2, 3)])
        assertEquals(StoneColor.BLACK, withStone[Point(2, 3)])
    }

    @Test
    fun `group and liberties follow connections`() {
        val board =
            Board
                .empty(9)
                .with(Point(0, 0), StoneColor.BLACK)
                .with(Point(1, 0), StoneColor.BLACK)
                .with(Point(1, 1), StoneColor.WHITE)
        val group = board.group(Point(0, 0))
        assertEquals(setOf(Point(0, 0), Point(1, 0)), group)
        assertEquals(setOf(Point(0, 1), Point(2, 0)), board.liberties(group))
    }

    @Test
    fun `neighbors are clipped at the edge`() {
        val board = Board.empty(9)
        assertEquals(setOf(Point(1, 0), Point(0, 1)), board.neighbors(Point(0, 0)).toSet())
        assertEquals(4, board.neighbors(Point(4, 4)).size)
    }

    @Test
    fun `rejects unsupported sizes`() {
        assertFailsWith<IllegalArgumentException> { Board.empty(3) }
        assertFailsWith<IllegalArgumentException> { Board.empty(26) }
    }

    @Test
    fun `hoshi positions per standard size`() {
        assertEquals(9, BoardSizes.hoshi(19).size)
        assertTrue(Point(3, 3) in BoardSizes.hoshi(19))
        assertTrue(Point(9, 9) in BoardSizes.hoshi(19))
        assertEquals(9, BoardSizes.hoshi(13).size)
        assertTrue(Point(3, 6) in BoardSizes.hoshi(13))
        assertEquals(9, BoardSizes.hoshi(9).size)
        assertTrue(Point(2, 2) in BoardSizes.hoshi(9))
        assertTrue(Point(4, 4) in BoardSizes.hoshi(9))
    }
}
