package dev.turnzen.game

import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.game.sgf.SgfParseException
import dev.turnzen.game.sgf.SgfParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SgfTest {
    private val dgsLike =
        """
        (
        ;FF[4]GM[1]
        AP[DGS:1.20.5]
        PC[Dragon Go Server: https://www.dragongoserver.net/]
        GN[white-black-123-20260101]
        PB[Black Name (blackhandle)]
        PW[White Name (whitehandle)]
        BR[14k]
        WR[9k]
        XM[7]
        GC[Game ID: 123
        Rated: Y]
        OT[30 days with 1 day extra per move]
        RU[Japanese]
        SZ[9]
        KM[0.5]
        HA[2]
        AB[gc][cg]
        MN[1]
        ;W[cc]C[hello \] world\\]
        ;B[ee]
        ;W[]
        ;B[tt]
        )
        """.trimIndent()

    @Test
    fun `parses a DGS style record`() {
        val game = SgfGameRecords.read(dgsLike)
        val record = game.record
        assertEquals(9, record.boardSize)
        assertEquals(0.5, record.komi)
        assertEquals(2, record.handicap)
        assertEquals(listOf(Point(6, 2), Point(2, 6)), record.setupBlack)
        assertEquals(4, record.moves.size)
        assertEquals(ColoredMove(StoneColor.WHITE, Move.Play(Point(2, 2))), record.moves[0])
        assertEquals(ColoredMove(StoneColor.BLACK, Move.Play(Point(4, 4))), record.moves[1])
        assertEquals(ColoredMove(StoneColor.WHITE, Move.Pass), record.moves[2])
        assertEquals(ColoredMove(StoneColor.BLACK, Move.Pass), record.moves[3])
        assertEquals("Black Name (blackhandle)", record.black.name)
        assertEquals("9k", record.white.rank)
        assertEquals(listOf("7"), game.rootProperties["XM"])
        assertEquals(StoneColor.WHITE, record.initialPosition.toMove)
        assertEquals(StoneColor.WHITE, record.nextToMove)
    }

    @Test
    fun `escapes inside values are decoded`() {
        val tree = SgfParser.parseFirst(dgsLike)
        val comment = tree.mainLine[1].first("C")
        assertEquals("hello ] world\\", comment)
    }

    @Test
    fun `comments are keyed by the moves played at their node`() {
        val text = "(;SZ[9]C[before anything];AB[cc]C[setup];B[aa]C[first];W[bb];B[]C[a pass\\] too])"
        val comments = SgfGameRecords.read(text).comments
        assertEquals(mapOf(0 to "before anything\n\nsetup", 1 to "first", 3 to "a pass] too"), comments)
    }

    @Test
    fun `main line follows the first variation`() {
        val text = "(;SZ[9];B[aa](;W[bb];B[cc])(;W[dd]))"
        val record = SgfGameRecords.read(text).record
        assertEquals(listOf(Point(0, 0), Point(1, 1), Point(2, 2)), record.moves.map { it.point })
    }

    @Test
    fun `replay produces the final position with captures`() {
        val text = "(;SZ[9];B[ba];W[aa];B[ab])"
        val record = SgfGameRecords.read(text).record
        val replay = Replay(record)
        assertEquals(4, replay.positions.size)
        assertEquals(null, replay.finalPosition.board[Point(0, 0)])
        assertEquals(1, replay.finalPosition.prisonersOf(StoneColor.BLACK))
        assertEquals(StoneColor.WHITE, replay.finalPosition.toMove)
        assertEquals(StoneColor.WHITE, replay.positionAfter(1).board[Point(0, 0)].let { it ?: StoneColor.WHITE })
    }

    @Test
    fun `writer output parses back to the same record`() {
        val record = SgfGameRecords.read(dgsLike).record
        val written = SgfGameRecords.write(record)
        val reread = SgfGameRecords.read(written).record
        assertEquals(record, reread)
    }

    @Test
    fun `malformed input fails loudly`() {
        assertFailsWith<SgfParseException> { SgfParser.parse("garbage") }
        assertFailsWith<SgfParseException> { SgfParser.parse("(;SZ[9]") }
        assertFailsWith<SgfParseException> { SgfGameRecords.read("(;SZ[19:13])") }
    }
}
