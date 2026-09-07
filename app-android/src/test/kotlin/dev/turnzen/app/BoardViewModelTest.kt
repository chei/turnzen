package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.board.BoardViewModel
import dev.turnzen.app.ui.board.PendingConfirmation
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest : MainDispatcherTest() {
    private val game = GameId("demo-9")

    private class Harness(
        client: TurnBasedGoServerClient,
    ) {
        val store = InMemoryCredentialStore()
        val session = sessionFor(client, store)
        val games = GamesStore(session)
        val sent = ArrayList<GameId>()
        lateinit var vm: BoardViewModel

        suspend fun start(id: GameId) {
            session.signIn(Credentials("demo", "pw"))
            games.refresh()
            vm = BoardViewModel(id, session, games) { sent += it }
            vm.load()
        }
    }

    @Test
    fun `loads the server position and shows the latest move`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            val s = h.vm.state.value
            assertFalse(s.loading)
            assertEquals(12, s.moveCount)
            assertTrue(s.atLatest)
            assertFalse(s.isTrial)
            assertNull(s.moveToSend)
            assertEquals(StoneColor.BLACK, s.detail?.summary?.myColor)
        }

    @Test
    fun `reload reflects a move made since the last load`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            assertEquals(12, h.vm.state.value.moveCount)

            // The game moves on elsewhere; reopening the board must show the new state.
            client.sendMove(game, MoveToken("12"), Move.Play(Point(0, 0)))
            h.vm.load()
            advanceUntilIdle()
            assertEquals(14, h.vm.state.value.moveCount)
        }

    @Test
    fun `on the opponent's turn the board explores their move and cannot send`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(GameId("demo-waiting"))
            advanceUntilIdle()

            val loaded = h.vm.state.value
            assertFalse(loaded.isMyTurn)
            assertEquals(StoneColor.BLACK, loaded.detail?.summary?.myColor)
            assertFalse(loaded.canSend)

            // First tap places the side to move: the opponent (white), then alternates.
            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            val s = h.vm.state.value
            assertEquals(listOf(StoneColor.WHITE, StoneColor.BLACK), s.trialMoves.map { it.color })
            // Even with a variation on the board, nothing can be sent on the opponent's turn.
            assertFalse(s.canSend)
        }

    @Test
    fun `taps build a local trial variation and never touch the server`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()

            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            h.vm.onTap(Point(1, 0)) // occupied, ignored
            val s = h.vm.state.value
            assertTrue(s.isTrial)
            assertEquals(2, s.trialMoves.size)
            assertEquals(listOf(StoneColor.BLACK, StoneColor.WHITE), s.trialMoves.map { it.color })
            assertEquals(Move.Play(Point(0, 0)), s.moveToSend?.move)
            assertEquals("A9", s.sendLabel)
            assertTrue(s.canSend)

            // The server record is untouched.
            assertEquals(
                12,
                client
                    .game(game)
                    .record.moves.size,
            )

            h.vm.undo()
            assertEquals(1, h.vm.state.value.trialMoves.size)
            h.vm.reset()
            assertFalse(h.vm.state.value.isTrial)
            assertFalse(h.vm.state.value.canSend)
        }

    @Test
    fun `send transmits only the first trial stone and advances`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            h.vm.onTap(Point(2, 0))

            h.vm.askSend()
            assertEquals(PendingConfirmation.SEND, h.vm.state.value.confirmation)
            h.vm.confirm()
            advanceUntilIdle()

            val record = client.game(game).record
            assertEquals(14, record.moves.size) // my move + the fake opponent's pass
            assertEquals(Move.Play(Point(0, 0)), record.moves[12].move)
            assertEquals(StoneColor.BLACK, record.moves[12].color)
            assertEquals(listOf(game), h.sent)
            assertNull(
                h.games.yourTurn.value
                    ?.firstOrNull { it.id == game },
            )
        }

    @Test
    fun `a stale game is reloaded and reported`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            // Opponent (well, someone) moved meanwhile.
            client.sendMove(game, MoveToken("12"), Move.Play(Point(8, 8)))

            h.vm.onTap(Point(0, 0))
            h.vm.askSend()
            h.vm.confirm()
            advanceUntilIdle()

            val s = h.vm.state.value
            assertNotNull(s.notice)
            assertEquals(14, s.moveCount)
            assertTrue(h.sent.isEmpty())
        }

    @Test
    fun `a network failure keeps the variation`() =
        runTest(dispatcher) {
            val failing =
                object : TurnBasedGoServerClient by FakeTurnBasedGoServerClient(latencyMillis = 0) {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                    ): Unit = throw GoServerException.Unreachable("offline")
                }
            val h = Harness(failing)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            h.vm.askSend()
            h.vm.confirm()
            advanceUntilIdle()

            val s = h.vm.state.value
            assertNotNull(s.error)
            assertEquals(2, s.trialMoves.size)
            assertTrue(s.canSend)
            assertTrue(h.sent.isEmpty())
        }

    @Test
    fun `history navigation is disabled while a trial is in progress`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            h.vm.showPrevious()
            h.vm.showPrevious()
            assertEquals(10, h.vm.state.value.viewingMove)
            assertEquals(
                10,
                h.vm.state.value.position
                    ?.board
                    ?.stones()
                    ?.size,
            )
            h.vm.onTap(Point(0, 0))
            assertTrue(h.vm.state.value.atLatest)
            h.vm.showPrevious()
            assertTrue(h.vm.state.value.atLatest)
        }
}
