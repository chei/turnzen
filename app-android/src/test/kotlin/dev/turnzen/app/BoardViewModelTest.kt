package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.board.BoardViewModel
import dev.turnzen.app.ui.board.PendingConfirmation
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
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
            assertTrue(s.atCurrent)
            assertTrue(s.atEnd)
            assertFalse(s.hasTrial)
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

            // The game moves on elsewhere. Once the list knows (it is refreshed whenever it is
            // shown), reopening the board downloads the record again and shows the new state.
            client.sendMove(game, MoveToken("12"), Move.Play(Point(0, 0)))
            h.games.refresh()
            advanceUntilIdle()
            h.vm.load()
            advanceUntilIdle()
            assertEquals(14, h.vm.state.value.moveCount)
        }

    @Test
    fun `reopening a game clears the trial and lands on the current position`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()

            // Explore a couple of moves, then leave and reopen (the cached ViewModel's load()).
            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            assertTrue(h.vm.state.value.hasTrial)

            h.vm.load()
            // The record already held is still current (the list agrees), so reopening lands
            // on the current position at once, with the previous viewing's trial gone.
            val reopened = h.vm.state.value
            assertFalse(reopened.loading)
            assertFalse(reopened.hasTrial)
            assertTrue(reopened.atCurrent)

            advanceUntilIdle()
            val loaded = h.vm.state.value
            assertFalse(loaded.hasTrial)
            assertTrue(loaded.atCurrent)
        }

    @Test
    fun `a prefetched game opens instantly without another download`() =
        runTest(dispatcher) {
            var downloads = 0
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val counting =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun game(id: GameId): GameDetail {
                        downloads++
                        return fake.game(id)
                    }
                }
            val h = Harness(counting)
            h.session.signIn(Credentials("demo", "pw"))
            h.games.refresh()
            advanceUntilIdle()

            // Warmed in the background, the way the queue's next game is.
            val warmed = GameId("demo-13")
            h.games.prefetch(warmed)
            advanceUntilIdle()
            assertEquals(1, downloads)

            val vm = BoardViewModel(warmed, h.session, h.games) { }
            vm.load()
            // Instantaneous: the record is already here, so there is no second download and
            // no loading state to sit through.
            val opened = vm.state.value
            assertFalse(opened.loading)
            assertNotNull(opened.position)
            advanceUntilIdle()
            assertEquals(1, downloads)
        }

    @Test
    fun `reopening a game that advanced on the server shows no stale position while loading`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            assertEquals(12, h.vm.state.value.moveCount)
            assertNotNull(h.vm.state.value.position)

            // The game moves on (the user played and/or the opponent did) and the games list
            // learns about it, but the board's cached replay is still the old one.
            client.sendMove(game, MoveToken("12"), Move.Play(Point(0, 0)))
            h.games.refresh()
            advanceUntilIdle()

            h.vm.load()
            // While the fresh record downloads, the board must not paint the stale position:
            // it falls back to the loading placeholder (no position) instead of flashing the
            // old board and then jumping.
            val whileLoading = h.vm.state.value
            assertTrue(whileLoading.loading)
            assertNull(whileLoading.position)

            advanceUntilIdle()
            assertEquals(14, h.vm.state.value.moveCount)
            assertNotNull(h.vm.state.value.position)
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
            h.vm.onTap(Point(0, 0)) // occupied by an earlier trial stone, ignored
            val s = h.vm.state.value
            assertTrue(s.hasTrial)
            assertEquals(2, s.trialMoves.size)
            assertEquals(listOf(StoneColor.BLACK, StoneColor.WHITE), s.trialMoves.map { it.color })
            assertEquals(Move.Play(Point(0, 0)), s.moveToSend?.move)
            assertEquals("A9", s.sendLabel)
            // Two moves deep, the send is disarmed: only a single move ahead may be sent.
            assertFalse(s.canSend)

            // The server record is untouched.
            assertEquals(
                12,
                client
                    .game(game)
                    .record.moves.size,
            )

            // Stepping back to a single move ahead arms the send.
            h.vm.stepBack()
            assertTrue(h.vm.state.value.canSend)
            assertEquals(1, h.vm.state.value.trialMoves.size)

            // Scrubbing to the current state disarms it again without discarding the trial.
            h.vm.toCurrent()
            assertFalse(h.vm.state.value.canSend)
            assertTrue(h.vm.state.value.hasTrial)
        }

    @Test
    fun `tapping the newest trial stone takes it back`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()

            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            assertEquals(2, h.vm.state.value.trialMoves.size)

            // Same effect as the step-back button: the stone goes, the variation is kept,
            // so stepping forward brings it back.
            h.vm.onTap(Point(1, 0))
            assertEquals(1, h.vm.state.value.trialMoves.size)
            assertEquals(2, h.vm.state.value.trialCount)
            h.vm.stepForward()
            assertEquals(2, h.vm.state.value.trialMoves.size)

            // Taking back every trial stone returns the board to the server position.
            h.vm.onTap(Point(1, 0))
            h.vm.onTap(Point(0, 0))
            assertTrue(h.vm.state.value.atCurrent)
            assertEquals(0, h.vm.state.value.trialMoves.size)
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

            // A three-move exploration cannot be sent; step back to a single move ahead first.
            h.vm.stepBack()
            h.vm.stepBack()
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
    fun `a move rejected as not your turn reloads the game and rechecks the list`() =
        runTest(dispatcher) {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            var listLoads = 0
            val client =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                    ): Unit = throw GoServerException.MoveRejected("not_your_turn", "not your turn")

                    override suspend fun gamesAwaitingMe(): List<GameSummary> {
                        listLoads++
                        return fake.gamesAwaitingMe()
                    }
                }
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            val loadsAfterStart = listLoads

            h.vm.onTap(Point(0, 0))
            h.vm.askSend()
            h.vm.confirm()
            advanceUntilIdle()

            val s = h.vm.state.value
            // Not a rejection the user can act on: the game moved on, so it is reloaded and
            // the games list is re-read rather than an error being shown.
            assertNotNull(s.notice)
            assertNull(s.problem)
            assertTrue(h.sent.isEmpty())
            assertEquals(loadsAfterStart + 1, listLoads)
        }

    @Test
    fun `a move rejected as illegal is reported and keeps the variation`() =
        runTest(dispatcher) {
            val rejecting =
                object : TurnBasedGoServerClient by FakeTurnBasedGoServerClient(latencyMillis = 0) {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                    ): Unit = throw GoServerException.MoveRejected("ko", "ko")
                }
            val h = Harness(rejecting)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.askSend()
            h.vm.confirm()
            advanceUntilIdle()

            val s = h.vm.state.value
            assertNotNull(s.problem)
            assertNull(s.notice)
            assertTrue(s.hasTrial)
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
            h.vm.askSend()
            h.vm.confirm()
            advanceUntilIdle()

            val s = h.vm.state.value
            assertNotNull(s.problem)
            assertEquals(1, s.trialMoves.size)
            assertTrue(s.canSend)
            assertTrue(h.sent.isEmpty())
        }

    @Test
    fun `move navigation scrubs the whole timeline`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            val vm = h.vm
            assertTrue(vm.state.value.atCurrent)
            assertTrue(vm.state.value.atEnd)

            vm.toStart()
            assertTrue(vm.state.value.atStart)
            assertEquals(
                0,
                vm.state.value.position
                    ?.board
                    ?.stones()
                    ?.size,
            )

            vm.stepForward()
            assertEquals(
                1,
                vm.state.value.position
                    ?.board
                    ?.stones()
                    ?.size,
            )

            vm.toCurrent()
            assertTrue(vm.state.value.atCurrent)

            // Two trial stones extend the timeline past the current state.
            vm.onTap(Point(0, 0))
            vm.onTap(Point(1, 0))
            assertTrue(vm.state.value.hasTrial)
            assertTrue(vm.state.value.atEnd)
            assertEquals(2, vm.state.value.trialMoves.size)

            // Scrub back into the trial: one stone shown, no longer at the end.
            vm.stepBack()
            assertEquals(1, vm.state.value.trialMoves.size)
            assertFalse(vm.state.value.atEnd)

            // Back at the current state: no trial stones shown, but the trial still exists.
            vm.toCurrent()
            assertEquals(0, vm.state.value.trialMoves.size)
            assertTrue(vm.state.value.hasTrial)

            // Jump to the end shows the whole trial again.
            vm.toEnd()
            assertEquals(2, vm.state.value.trialMoves.size)
        }

    @Test
    fun `tapping the last played stone rewinds past it`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            val replay = h.vm.state.value.replay!!
            assertEquals(12, h.vm.state.value.cursor)

            // The newest stone on the board is the last played move; tapping it steps behind
            // it, the same as the back button. Nothing is placed and the game is untouched.
            h.vm.onTap((replay.record.moves[11].move as Move.Play).point)
            assertEquals(11, h.vm.state.value.cursor)
            assertFalse(h.vm.state.value.hasTrial)

            // Repeating walks further back: now the newest stone is the move before it.
            h.vm.onTap((replay.record.moves[10].move as Move.Play).point)
            assertEquals(10, h.vm.state.value.cursor)
            assertEquals(12, h.vm.state.value.moveCount)
        }

    @Test
    fun `a trial can branch from a rewound position but cannot be sent`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            val vm = h.vm
            assertTrue(vm.state.value.isMyTurn)

            // Rewind six moves into the game and ask "what if I had played here instead".
            repeat(6) { vm.stepBack() }
            assertEquals(6, vm.state.value.cursor)
            vm.onTap(Point(0, 0))

            val branched = vm.state.value
            assertTrue(branched.hasTrial)
            assertEquals(6, branched.trialStart)
            assertEquals(7, branched.cursor)
            assertEquals(1, branched.trialMoves.size)
            assertFalse(branched.atCurrent)
            // The line answers a question the game has moved beyond, so it is not sendable
            // even though it is the user's turn and it is one move deep.
            assertFalse(branched.trialFromCurrent)
            assertFalse(branched.canSend)
            assertNull(branched.moveToSend)

            // Returning to the live position drops the what-if line.
            vm.toCurrent()
            val back = vm.state.value
            assertTrue(back.atCurrent)
            assertEquals(12, back.cursor)
            assertFalse(back.hasTrial)
        }

    @Test
    fun `tapping while scrubbed into the trial branches a new line`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            val vm = h.vm
            vm.onTap(Point(0, 0))
            vm.onTap(Point(1, 0))
            vm.onTap(Point(2, 0))
            assertEquals(3, vm.state.value.trialCount)

            // Step back to depth 2, then tap: the trial truncates and a new move is added.
            vm.stepBack()
            vm.onTap(Point(3, 0))
            assertEquals(3, vm.state.value.trialCount)
            assertEquals(
                Point(3, 0),
                vm.state.value.trialMoves
                    .last()
                    .point,
            )
        }
}
