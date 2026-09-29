package dev.turnzen.game

import dev.turnzen.game.sgf.SgfCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinatesTest {
    @Test
    fun `labels skip the letter I and count rows from the bottom`() {
        assertEquals("A19", Coordinates.label(Point(0, 0), 19))
        assertEquals("T1", Coordinates.label(Point(18, 18), 19))
        assertEquals("J10", Coordinates.label(Point(8, 9), 19))
        assertEquals("O15", Coordinates.label(Point(13, 4), 19))
        assertEquals("D4", Coordinates.label(Point(3, 15), 19))
        assertEquals("E5", Coordinates.label(Point(4, 4), 9))
    }

    @Test
    fun `labels parse back`() {
        assertEquals(Point(13, 4), Coordinates.parse("o15", 19))
        assertEquals(Point(8, 9), Coordinates.parse("J10", 19))
        assertNull(Coordinates.parse("I10", 19))
        assertNull(Coordinates.parse("K10", 9))
        assertNull(Coordinates.parse("A0", 9))
    }

    @Test
    fun `sgf coordinates round trip`() {
        assertEquals("aa", SgfCoordinates.encode(Point(0, 0)))
        assertEquals("qd", SgfCoordinates.encode(Point(16, 3)))
        assertEquals(Point(16, 3), SgfCoordinates.decodePoint("qd"))
        assertNull(SgfCoordinates.decodeMove("", 19))
        assertNull(SgfCoordinates.decodeMove("tt", 19))
        assertEquals(Point(19, 19), SgfCoordinates.decodeMove("tt", 21))
    }

    @Test
    fun `compressed point lists expand`() {
        assertEquals(4, SgfCoordinates.decodePointList("aa:bb").size)
    }
}
