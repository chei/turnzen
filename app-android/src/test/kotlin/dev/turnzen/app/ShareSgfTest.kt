package dev.turnzen.app

import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.common.sgfFile
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareSgfTest {
    @Test
    fun `the file holds the moves and names who played, from the game's details where the record lacks them`() =
        runTest {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            fake.signIn(Credentials("demo", "pw"))
            val detail = fake.game(GameId(DEMO_SERVER, "demo-9"))
            val bare = detail.copy(record = detail.record.copy(black = PlayerInfo(), white = PlayerInfo()))

            val sgf = sgfFile(bare)

            val back = SgfGameRecords.read(sgf.text).record
            assertEquals(detail.record.moves, back.moves)
            assertEquals(detail.info!!.black.handle, back.black.name)
            assertEquals(detail.info!!.white.handle, back.white.name)
            assertEquals("${back.black.name}-${back.white.name}-demo-9.sgf", sgf.name)
        }

    @Test
    fun `a file name keeps only characters any app accepts`() =
        runTest {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            fake.signIn(Credentials("demo", "pw"))
            val detail = fake.game(GameId(DEMO_SERVER, "demo-9"))
            val odd = detail.copy(record = detail.record.copy(black = PlayerInfo("a/b c"), white = PlayerInfo("Ödön")))
            val name = sgfFile(odd).name
            assertTrue(name.startsWith("a_b_c-_d_n-"), name)
        }
}
