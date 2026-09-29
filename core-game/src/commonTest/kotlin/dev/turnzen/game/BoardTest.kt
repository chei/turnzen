package dev.turnzen.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun `points past either edge are off the board`() {
        val board = Board.empty(9)
        assertFalse(Point(9, 0) in board, "one past the right edge")
        assertFalse(Point(0, 9) in board, "one past the bottom edge")
        assertTrue(Point(8, 8) in board, "the far corner is still on it")
        assertFailsWith<IllegalArgumentException> { board[Point(9, 0)] }
        assertFailsWith<IllegalArgumentException> { board.with(Point(0, 9), StoneColor.BLACK) }
    }

    @Test
    fun `boards with the same stones are equal`() {
        // Board equality backs position comparisons; two boards built in a different
        // order but holding the same stones must not look different.
        val a = Board.empty(9).with(Point(2, 3), StoneColor.BLACK).with(Point(4, 4), StoneColor.WHITE)
        val b = Board.empty(9).with(Point(4, 4), StoneColor.WHITE).with(Point(2, 3), StoneColor.BLACK)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == Board.empty(9))
        assertFalse(a == Board.empty(13).with(Point(2, 3), StoneColor.BLACK).with(Point(4, 4), StoneColor.WHITE))
    }

    @Test
    fun `stones and counts report what is on the board`() {
        val board =
            Board
                .empty(9)
                .with(Point(2, 3), StoneColor.BLACK)
                .with(Point(4, 4), StoneColor.WHITE)
                .with(Point(8, 0), StoneColor.BLACK)
        assertEquals(2, board.count(StoneColor.BLACK))
        assertEquals(1, board.count(StoneColor.WHITE))
        // stones() reconstructs the point from the flat index; a col/row mix-up shows here.
        assertEquals(
            setOf(Point(2, 3) to StoneColor.BLACK, Point(4, 4) to StoneColor.WHITE, Point(8, 0) to StoneColor.BLACK),
            board.stones().toSet(),
        )
    }
}
