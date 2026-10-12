package dev.turnzen.app

import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.MoveOutbox
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.board.BoardEventKind
import dev.turnzen.app.ui.board.BoardViewModel
import dev.turnzen.app.ui.board.PendingConfirmation
import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Coordinates
import dev.turnzen.game.DeadStones
import dev.turnzen.game.GameResult
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.ResultReason
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameComment
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest : MainDispatcherTest() {
    private val game = GameId(DEMO_SERVER, "demo-9")
    private val scoringGame = GameId(DEMO_SERVER, "demo-scoring")

    /** The demo archive: a counted game ending in two passes, and one that was resigned. */
    private val countedGame = GameId(DEMO_SERVER, "demo-done-win")
    private val resignedGame = GameId(DEMO_SERVER, "demo-done-loss")

    /** The white stone stranded in black's area in the demo server's scoring game (D8). */
    private val strandedStone = checkNotNull(Coordinates.parse("D8", 9))

    private class Harness(
        client: TurnBasedGoServerClient,
        /** Off unless a test is about it, so the others start from the server's own marks. */
        private val guess: Boolean = false,
    ) {
        val store = InMemoryCredentialStore()
        val session = sessionFor(client, store)
        val accounts = Accounts(listOf(session))
        val games = GamesStore(accounts)

        // Main is the test dispatcher, so sends run on virtual time.
        val outbox = MoveOutbox(accounts, games, CoroutineScope(Dispatchers.Main))
        val sent = ArrayList<GameId>()
        var ended = 0
        lateinit var vm: BoardViewModel

        suspend fun start(
            id: GameId,
            finished: Boolean = false,
        ) {
            session.signIn(Credentials("demo", "pw"))
            games.refresh()
            if (finished) games.refreshFinished()
            vm =
                BoardViewModel(
                    id,
                    session,
                    games,
                    outbox,
                    onGameEnded = { ended++ },
                    guessDeadStones = if (guess) ({ DeadStones.guessTaps(it) }) else ({ emptyList() }),
                    // The guess stays on the test dispatcher, so advanceUntilIdle waits for it.
                    computeContext = EmptyCoroutineContext,
                ) { sent += it }
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
            val warmed = GameId(DEMO_SERVER, "demo-13")
            h.games.prefetch(warmed)
            advanceUntilIdle()
            assertEquals(1, downloads)

            val vm = BoardViewModel(warmed, h.session, h.games, h.outbox) { }
            vm.load()
            // Instantaneous: the record is already here, so there is no second download and
            // no loading state to sit through.
            val opened = vm.state.value
            assertFalse(opened.loading)
            assertNotNull(opened.position)
            advanceUntilIdle()
            assertEquals(1, downloads)
        }

    /** [client] with every move refused for want of a connection. */
    private fun failingSends(client: TurnBasedGoServerClient): TurnBasedGoServerClient =
        object : TurnBasedGoServerClient by client {
            override suspend fun sendMove(
                id: GameId,
                token: MoveToken,
                move: Move,
                comment: String?,
            ): Unit = throw GoServerException.Unreachable("offline")
        }

    /** The demo server, with the clocks a test sets in place of the ones it reports. */
    private class ClockedClient(
        private val fake: FakeTurnBasedGoServerClient,
    ) : TurnBasedGoServerClient by fake {
        var downloads = 0

        /** What the games list says is left on the user's clock. */
        var listClock: RemainingTime? = null

        /** What a downloaded record says is left on the opponent's clock. */
        var opponentClock: RemainingTime? = null

        private fun GameSummary.clocked() = listClock?.let { copy(remainingTime = it) } ?: this

        override suspend fun gamesAwaitingMe() = fake.gamesAwaitingMe().map { it.clocked() }

        override suspend fun myRunningGames() = fake.myRunningGames().map { it.clocked() }

        override suspend fun game(id: GameId): GameDetail {
            downloads++
            val detail = fake.game(id)
            val clock = opponentClock ?: return detail
            val info = checkNotNull(detail.info)
            return detail.copy(
                info =
                    if (detail.summary.myColor == StoneColor.BLACK) {
                        info.copy(white = info.white.copy(remainingTime = clock))
                    } else {
                        info.copy(black = info.black.copy(remainingTime = clock))
                    },
            )
        }
    }

    @Test
    fun `a held record shows the user's clock as the games list has it now`() =
        runTest(dispatcher) {
            val client = ClockedClient(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val h = Harness(client)
            h.session.signIn(Credentials("demo", "pw"))
            h.games.refresh()
            val warmed = GameId(DEMO_SERVER, "demo-13")
            h.games.prefetch(warmed)
            advanceUntilIdle()
            val held = checkNotNull(h.games.cachedDetail(warmed))
            assertEquals("6h", held.summary.remainingTime?.text)

            // Hours later nobody has moved, so the held record is still the game — but the
            // user's clock has been running, and the list (refreshed meanwhile) says so.
            client.listClock = RemainingTime("3h", 3.hours)
            h.games.refresh()
            advanceUntilIdle()

            val vm = BoardViewModel(warmed, h.session, h.games, h.outbox) { }
            vm.load()
            advanceUntilIdle()
            val detail = checkNotNull(vm.state.value.detail)
            assertEquals("3h", detail.summary.remainingTime?.text)
            val mine = if (detail.summary.myColor == StoneColor.BLACK) detail.info?.black else detail.info?.white
            assertEquals("3h", mine?.remainingTime?.text)
            // Still the held record: correcting the clock costs no download.
            assertEquals(1, client.downloads)
        }

    @Test
    fun `on the opponent's turn the info sheet fetches their running clock once`() =
        runTest(dispatcher) {
            val client = ClockedClient(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val h = Harness(client)
            h.session.signIn(Credentials("demo", "pw"))
            h.games.refresh()
            val waiting = GameId(DEMO_SERVER, "demo-waiting")
            h.games.prefetch(waiting)
            advanceUntilIdle()

            val vm = BoardViewModel(waiting, h.session, h.games, h.outbox) { }
            vm.load()
            advanceUntilIdle()
            assertEquals(1, client.downloads)

            // The opponent's clock has run since the record was held; only the game knows.
            client.opponentClock = RemainingTime("4h", 4.hours)
            vm.onInfoOpened()
            advanceUntilIdle()
            assertEquals(2, client.downloads)
            val detail = checkNotNull(vm.state.value.detail)
            val theirs = if (detail.summary.myColor == StoneColor.BLACK) detail.info?.white else detail.info?.black
            assertEquals("4h", theirs?.remainingTime?.text)

            // Opening the sheet again asks for nothing more.
            vm.onInfoOpened()
            advanceUntilIdle()
            assertEquals(2, client.downloads)
        }

    @Test
    fun `on the user's own turn the info sheet asks for nothing`() =
        runTest(dispatcher) {
            val client = ClockedClient(FakeTurnBasedGoServerClient(latencyMillis = 0))
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            assertEquals(1, client.downloads)
            h.vm.onInfoOpened()
            advanceUntilIdle()
            assertEquals(1, client.downloads)
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
            h.start(GameId(DEMO_SERVER, "demo-waiting"))
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
    fun `passing sends a pass and steps the queue on like any other move`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            val before =
                client
                    .game(game)
                    .record.moves.size

            h.vm.askPass()
            assertEquals(PendingConfirmation.PASS, h.vm.state.value.confirmation)
            h.vm.confirm()
            advanceUntilIdle()

            // A pass is a move: it reaches the server and the queue advances past the game.
            assertEquals(
                Move.Pass,
                client
                    .game(game)
                    .record.moves[before]
                    .move,
            )
            assertEquals(listOf(game), h.sent)
            assertEquals(0, h.ended)
        }

    @Test
    fun `pass takes the send slot only when the opponent has passed and nothing is armed`() =
        runTest(dispatcher) {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            // An ordinary position: passing is possible, but stays out of the action row.
            assertTrue(h.vm.state.value.canPass)
            assertFalse(h.vm.state.value.opponentPassed)
            assertFalse(h.vm.state.value.passPromoted)

            // The same game after the opponent has passed.
            val passed =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun game(id: GameId): GameDetail {
                        val detail = fake.game(id)
                        val record = detail.record
                        return detail.copy(
                            record = record.copy(moves = record.moves + ColoredMove(StoneColor.WHITE, Move.Pass)),
                        )
                    }
                }
            val offered = Harness(passed)
            offered.start(game)
            advanceUntilIdle()
            assertTrue(offered.vm.state.value.opponentPassed)
            assertTrue(offered.vm.state.value.passPromoted)

            // An armed trial stone is a move to send, so Send takes the slot back...
            offered.vm.onTap(Point(0, 0))
            assertTrue(offered.vm.state.value.canSend)
            assertFalse(offered.vm.state.value.passPromoted)

            // ...and stepping off it disarms Send, so the pass on offer returns.
            offered.vm.stepBack()
            assertFalse(offered.vm.state.value.canSend)
            assertTrue(offered.vm.state.value.passPromoted)
        }

    @Test
    fun `a game in the counting phase opens in scoring mode`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(scoringGame)
            advanceUntilIdle()

            val s = h.vm.state.value
            val scoring = assertNotNull(s.scoring)
            assertTrue(s.isScoring)
            // No trial play in a game that has no moves left: taps mark instead.
            assertFalse(s.canPlace)
            assertTrue(s.canMark)
            assertFalse(scoring.changed)
            // Nothing is marked yet, so the stranded white stone keeps black's area neutral.
            assertTrue(scoring.dead.isEmpty())
            assertTrue(scoring.blackTerritory.isEmpty())
            // Server and device count the same position the same way: nothing to reconcile.
            assertNull(scoring.localAside)
        }

    @Test
    fun `a server count the device cannot reproduce is owned up to`() =
        runTest(dispatcher) {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val generous =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun scoreState(
                        id: GameId,
                        token: MoveToken,
                        toggle: List<Point>,
                    ): ScoreState {
                        // A count nobody's arithmetic reaches, standing in for the real
                        // reasons two counts part company: seki, or unfilled dame.
                        val real = fake.scoreState(id, token, toggle)
                        return real.copy(result = GameResult(StoneColor.BLACK, 10.0))
                    }
                }
            val h = Harness(generous)
            h.start(scoringGame)
            advanceUntilIdle()

            val scoring = assertNotNull(h.vm.state.value.scoring)
            // The headline stays the server's — that is what an agreement agrees to — and the
            // device's own figure, which the breakdown adds up to, is offered beside it.
            assertEquals(10.0, scoring.result?.margin)
            assertEquals(scoring.count?.result, scoring.localAside)

            // Once the user marks something the device's count is the only one on show, so
            // there are no longer two answers to reconcile.
            h.vm.toggleDead(strandedStone)
            advanceUntilIdle()
            assertNull(
                h.vm.state.value.scoring
                    ?.localAside,
            )
        }

    @Test
    fun `marking a group is local, and the count follows it`() =
        runTest(dispatcher) {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            var reads = 0
            val counting =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun scoreState(
                        id: GameId,
                        token: MoveToken,
                        toggle: List<Point>,
                    ): ScoreState {
                        reads++
                        return fake.scoreState(id, token, toggle)
                    }
                }
            val h = Harness(counting)
            h.start(scoringGame)
            advanceUntilIdle()
            assertEquals(1, reads) // the state is read once, when the board opens

            h.vm.toggleDead(strandedStone)
            advanceUntilIdle()

            val scoring = assertNotNull(h.vm.state.value.scoring)
            assertTrue(scoring.changed)
            assertTrue(strandedStone in scoring.dead)
            // Marking is a board gesture, so it never reaches the server.
            assertEquals(1, reads)
            // With the stone lifted, the top of the board is black's: eighteen points and the
            // prisoner itself.
            assertEquals(18, scoring.blackTerritory.size)
            assertEquals(19.0, scoring.count?.blackPoints)
            // One tap, so the server will hear about exactly one point.
            assertEquals(listOf(strandedStone), scoring.taps)
        }

    @Test
    fun `a board nobody has marked opens with the guessed dead stones`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client, guess = true)
            h.start(scoringGame)
            advanceUntilIdle()

            val scoring = assertNotNull(h.vm.state.value.scoring)
            assertTrue(scoring.guessed)
            assertEquals(setOf(strandedStone), scoring.dead)
            // The guess is marks like any other: sent as taps, or dropped with Reset marks.
            assertTrue(scoring.changed)
            assertEquals(listOf(strandedStone), scoring.taps)
            assertEquals(19.0, scoring.count?.blackPoints)

            h.vm.resetMarks()
            val reset = assertNotNull(h.vm.state.value.scoring)
            assertFalse(reset.guessed)
            assertTrue(reset.dead.isEmpty())
        }

    @Test
    fun `the 13x13 demo opens with both dead corners marked, two taps to send`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0), guess = true)
            h.start(GameId(DEMO_SERVER, "demo-scoring-13"))
            advanceUntilIdle()

            val scoring = assertNotNull(h.vm.state.value.scoring)
            val dead =
                listOf("L12", "N12", "M11", "N10", "M9", "N8", "K3", "L3", "M3", "N3", "N4", "N5", "M5", "N6")
                    .map { checkNotNull(Coordinates.parse(it, 13)) }
                    .toSet()
            assertTrue(scoring.guessed)
            assertEquals(dead, scoring.dead)
            assertEquals(2, scoring.taps.size)
        }

    @Test
    fun `marks already on the server are shown as they are, not guessed over`() =
        runTest(dispatcher) {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            // The opponent marked a stone of black's wall: the user is there to review that.
            val wallStone = checkNotNull(Coordinates.parse("A7", 9))
            val marked =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun scoreState(
                        id: GameId,
                        token: MoveToken,
                        toggle: List<Point>,
                    ): ScoreState {
                        val real = fake.scoreState(id, token, toggle)
                        return real.copy(deadStones = real.deadStones + wallStone)
                    }
                }
            val h = Harness(marked, guess = true)
            h.start(scoringGame)
            advanceUntilIdle()
            val scoring = assertNotNull(h.vm.state.value.scoring)
            assertFalse(scoring.guessed)
            assertFalse(scoring.changed)
            assertTrue(wallStone in scoring.dead)
            assertFalse(strandedStone in scoring.dead)
        }

    @Test
    fun `a counted game can be reviewed, and the marks wait at the end`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(scoringGame)
            advanceUntilIdle()
            h.vm.toggleDead(strandedStone)

            h.vm.stepBack()
            val back = h.vm.state.value
            assertFalse(back.showingScore)
            assertTrue(back.isScoring)
            // Off the end the board is for reading: taps try moves, nothing marks or settles.
            assertTrue(back.canPlace)
            assertFalse(back.canMark)
            assertFalse(back.canSettleScore)
            h.vm.toggleDead(strandedStone)
            assertEquals(listOf(strandedStone), back.scoring?.taps)

            h.vm.toCurrent()
            val home = h.vm.state.value
            assertTrue(home.showingScore)
            assertTrue(home.canMark)
            assertFalse(home.canPlace)
            assertEquals(setOf(strandedStone), home.scoring?.dead)
        }

    @Test
    fun `a line branched out of a counted game never stands in for the count`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(scoringGame)
            advanceUntilIdle()
            val end = h.vm.state.value.moveCount

            // One move back and a stone of one's own: the line ends on the final move number
            // without being the final position.
            h.vm.stepBack()
            val board = assertNotNull(h.vm.state.value.position).board
            (0 until 9)
                .flatMap { c -> (0 until 9).map { r -> Point(c, r) } }
                .filter { board[it] == null }
                .first { h.vm.onTap(it).let { h.vm.state.value.hasTrial } }
            val s = h.vm.state.value
            assertEquals(end, s.cursor)
            assertFalse(s.showingScore)
            assertFalse(s.canMark)

            h.vm.toCurrent()
            assertTrue(h.vm.state.value.showingScore)
        }

    @Test
    fun `marking the same group twice leaves nothing to tell the server`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(scoringGame)
            advanceUntilIdle()

            h.vm.toggleDead(strandedStone)
            h.vm.toggleDead(strandedStone)

            val scoring = assertNotNull(h.vm.state.value.scoring)
            assertFalse(scoring.changed)
            assertTrue(scoring.dead.isEmpty())
        }

    @Test
    fun `sending marks tells the server the groups that changed and steps the queue on`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(scoringGame)
            advanceUntilIdle()

            val token = assertNotNull(h.vm.state.value.detail).summary.moveToken
            h.vm.toggleDead(strandedStone)
            h.vm.askSendMarks()
            assertEquals(PendingConfirmation.SEND_MARKS, h.vm.state.value.confirmation)
            h.vm.confirm()
            advanceUntilIdle()

            // The server now holds the mark, and the game moved on like any other move.
            val onServer = client.scoreState(scoringGame, token)
            assertTrue(strandedStone in onServer.deadStones)
            assertEquals(listOf(scoringGame), h.sent)
        }

    @Test
    fun `accepting the score is offered only when the marks are untouched, and ends the game`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(scoringGame)
            advanceUntilIdle()

            // With marks pending, agreeing would contradict them: the server rejects that
            // combination, so the view model never offers it.
            h.vm.toggleDead(strandedStone)
            h.vm.askAcceptScore()
            assertNull(h.vm.state.value.confirmation)

            h.vm.resetMarks()
            h.vm.askAcceptScore()
            assertEquals(PendingConfirmation.ACCEPT_SCORE, h.vm.state.value.confirmation)
            h.vm.confirm()
            advanceUntilIdle()

            // The game is over: it has left the running lists for the archive, where it is
            // still readable — that is what the Finished tab shows.
            assertTrue(client.myRunningGames().none { it.id == scoringGame })
            assertTrue(client.game(scoringGame).summary.isFinished)
        }

    @Test
    fun `resigning ends the game, drops it from the lists and closes the board`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()

            h.vm.askResign()
            assertEquals(PendingConfirmation.RESIGN, h.vm.state.value.confirmation)
            h.vm.confirm()
            advanceUntilIdle()

            // Not a move: the queue does not step on, the board simply closes.
            assertEquals(1, h.ended)
            assertTrue(h.sent.isEmpty())
            assertNull(h.games.summary(game))
            assertTrue(client.myRunningGames().none { it.id == game })
            // Resigned, not gone: the archive keeps it, and says who won and how.
            val resigned = client.game(game).summary
            assertTrue(resigned.isFinished)
            assertEquals(ResultReason.RESIGNATION, resigned.result?.by)
            assertEquals(StoneColor.WHITE, resigned.result?.winner)
        }

    @Test
    fun `a game counted into two passes opens on the last stone, and names the passes`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(countedGame, finished = true)
            advanceUntilIdle()

            val opened = h.vm.state.value
            // Twelve stones, then the two passes that took the game to counting.
            assertEquals(14, opened.moveCount)
            assertEquals(12, opened.cursor)
            // Nothing to explain where the board stands: a stone was played there.
            assertNull(opened.eventAtCursor)

            // Forward walks into the passes, and now the board says what they are.
            h.vm.stepForward()
            val passed = assertNotNull(h.vm.state.value.eventAtCursor)
            assertEquals(BoardEventKind.PASS, passed.kind)
            assertEquals(StoneColor.BLACK, passed.by)

            h.vm.stepForward()
            assertEquals(StoneColor.WHITE, assertNotNull(h.vm.state.value.eventAtCursor).by)

            // And back out of them again.
            h.vm.stepBack()
            h.vm.stepBack()
            h.vm.stepBack()
            assertNull(h.vm.state.value.eventAtCursor)
        }

    @Test
    fun `the end of a resigned game says who resigned`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(resignedGame, finished = true)
            advanceUntilIdle()

            val s = h.vm.state.value
            // Nothing to step over: a resignation is not a move, so the last stone is the end.
            assertEquals(s.moveCount, s.cursor)
            val ended = assertNotNull(s.eventAtCursor)
            assertEquals(BoardEventKind.RESIGNATION, ended.kind)
            // The demo user played white and lost, so they are the one who resigned.
            assertEquals(StoneColor.WHITE, ended.by)

            // A step back is an ordinary position again.
            h.vm.stepBack()
            assertNull(h.vm.state.value.eventAtCursor)
        }

    @Test
    fun `a pass in a running game is reported too, which is why the Pass button appeared`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            h.vm.askPass()
            h.vm.confirm()
            advanceUntilIdle()
            h.vm.load(force = true)
            advanceUntilIdle()

            // The demo opponent answers a pass with a pass, so the board's last move is one.
            val event = assertNotNull(h.vm.state.value.eventAtCursor)
            assertEquals(BoardEventKind.PASS, event.kind)
        }

    @Test
    fun `tapping the point the game played next replays it instead of branching`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            // demo-9's last played move.
            val lastPlayed = checkNotNull(Coordinates.parse("D4", 9))

            h.vm.stepBack()
            h.vm.onTap(lastPlayed)

            val s = h.vm.state.value
            // Back on the game's own line, not holding a variation that merely matches it.
            assertFalse(s.hasTrial)
            assertEquals(s.moveCount, s.cursor)
            assertTrue(s.atCurrent)
        }

    @Test
    fun `tapping any other point from the same place still branches a what-if`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()

            h.vm.stepBack()
            h.vm.onTap(Point(0, 0))

            val s = h.vm.state.value
            assertTrue(s.hasTrial)
            assertEquals(s.moveCount - 1, s.trialStart)
            // The number matches the server's move count, but the line does not: this is a
            // what-if about move 12, so the board is not the current position.
            assertEquals(s.moveCount, s.cursor)
            assertFalse(s.atCurrent)
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
            h.vm.send()
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
            h.vm.send()
            advanceUntilIdle()
            assertTrue(
                h.outbox.failed.value!!
                    .movedOn,
            )

            // Back on the board: the real position, and a line saying why.
            h.vm.load()
            advanceUntilIdle()
            val s = h.vm.state.value
            assertNotNull(s.notice)
            assertEquals(14, s.moveCount)
            assertEquals(0, s.trialMoves.size)
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
                        comment: String?,
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
            h.vm.send()
            advanceUntilIdle()
            assertEquals(loadsAfterStart + 1, listLoads)

            h.vm.load()
            advanceUntilIdle()
            val s = h.vm.state.value
            // Not a rejection the user can act on: the game moved on, so it is reloaded and
            // the games list is re-read rather than an error being shown.
            assertNotNull(s.notice)
            assertNull(s.problem)
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
                        comment: String?,
                    ): Unit = throw GoServerException.MoveRejected("ko", "ko")
                }
            val h = Harness(rejecting)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.send()
            advanceUntilIdle()
            h.vm.load()
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
                        comment: String?,
                    ): Unit = throw GoServerException.Unreachable("offline")
                }
            val h = Harness(failing)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.send()
            advanceUntilIdle()
            // The board had already stepped on; the game is back in the queue, and the line
            // says the move did not go.
            assertEquals(listOf(game), h.sent)
            assertNotNull(h.outbox.failed.value)
            assertNotNull(
                h.games.yourTurn.value
                    ?.firstOrNull { it.id == game },
            )

            h.vm.load()
            advanceUntilIdle()
            val s = h.vm.state.value
            assertNotNull(s.problem)
            assertEquals(1, s.trialMoves.size)
            assertTrue(s.canSend)
            assertNull(h.outbox.failed.value)
        }

    @Test
    fun `the opponent's message is on show at the current position`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            assertEquals(
                listOf("Your move, Demo!"),
                h.vm.state.value.commentsAtCursor
                    .map { it.text },
            )

            // It belongs to the move it came with: one step back and it is gone...
            h.vm.stepBack()
            assertEquals(emptyList(), h.vm.state.value.commentsAtCursor)

            // ...while a trial stone tried in answer to it keeps it in view.
            h.vm.toCurrent()
            h.vm.onTap(Point(0, 0))
            assertEquals(
                listOf("Your move, Demo!"),
                h.vm.state.value.commentsAtCursor
                    .map { it.text },
            )
        }

    @Test
    fun `a comment goes with the move and is then cleared`() =
        runTest(dispatcher) {
            val comments = ArrayList<String?>()
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val recording =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                        comment: String?,
                    ) {
                        comments += comment
                        fake.sendMove(id, token, move, comment)
                    }
                }
            val h = Harness(recording)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.onCommentChange("  Thanks, you too  ")
            h.vm.send()
            advanceUntilIdle()

            assertEquals(listOf<String?>("Thanks, you too"), comments)
            assertEquals("", h.vm.state.value.commentDraft)
        }

    @Test
    fun `a move without a comment sends none`() =
        runTest(dispatcher) {
            val comments = ArrayList<String?>()
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val recording =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                        comment: String?,
                    ) {
                        comments += comment
                    }
                }
            val h = Harness(recording)
            h.start(game)
            advanceUntilIdle()
            h.vm.askPass()
            h.vm.onConfirmationCommentChange("   ")
            h.vm.confirm()
            advanceUntilIdle()

            assertEquals(listOf<String?>(null), comments)
        }

    @Test
    fun `the pass dialog starts from the board's comment, and cancelling it keeps the draft`() =
        runTest(dispatcher) {
            val comments = ArrayList<String?>()
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val recording =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                        comment: String?,
                    ) {
                        comments += comment
                        fake.sendMove(id, token, move, comment)
                    }
                }
            val h = Harness(recording)
            h.start(game)
            advanceUntilIdle()
            h.vm.onCommentChange("gg")

            h.vm.askPass()
            assertEquals("gg", h.vm.state.value.confirmationComment)
            h.vm.onConfirmationCommentChange("never mind")
            h.vm.cancelConfirmation()
            assertEquals("gg", h.vm.state.value.commentDraft)

            h.vm.askPass()
            h.vm.onConfirmationCommentChange("Thanks for the game")
            h.vm.confirm()
            advanceUntilIdle()

            assertEquals(listOf<String?>("Thanks for the game"), comments)
            assertEquals("", h.vm.state.value.commentDraft)
            assertEquals("", h.vm.state.value.confirmationComment)
        }

    @Test
    fun `a resignation takes the comment written in its dialog`() =
        runTest(dispatcher) {
            val comments = ArrayList<String?>()
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val recording =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun resign(
                        id: GameId,
                        token: MoveToken,
                        comment: String?,
                    ) {
                        comments += comment
                        fake.resign(id, token, comment)
                    }
                }
            val h = Harness(recording)
            h.start(game)
            advanceUntilIdle()
            h.vm.onCommentChange("a move I was going to make")

            h.vm.askResign()
            assertEquals("a move I was going to make", h.vm.state.value.confirmationComment)
            h.vm.onConfirmationCommentChange("  Well played  ")
            h.vm.confirm()
            advanceUntilIdle()

            assertEquals(listOf<String?>("Well played"), comments)
            assertEquals(1, h.ended)
            assertEquals(
                "Well played",
                fake
                    .game(game)
                    .comments
                    ?.last()
                    ?.text,
            )
        }

    @Test
    fun `a failed send keeps the comment for the retry`() =
        runTest(dispatcher) {
            val failing =
                object : TurnBasedGoServerClient by FakeTurnBasedGoServerClient(latencyMillis = 0) {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                        comment: String?,
                    ): Unit = throw GoServerException.Unreachable("offline")
                }
            val h = Harness(failing)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.onCommentChange("good luck")
            h.vm.send()
            advanceUntilIdle()
            h.vm.load()
            advanceUntilIdle()

            assertEquals("good luck", h.vm.state.value.commentDraft)
        }

    @Test
    fun `send steps on at once and the move goes to the server straight away`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.send()

            // No dialog: the queue moves on and the game leaves it...
            assertNull(h.vm.state.value.confirmation)
            assertEquals(listOf(game), h.sent)
            assertNull(
                h.games.yourTurn.value
                    ?.firstOrNull { it.id == game },
            )
            // ...and no wait: the move is on the server without any time passing.
            runCurrent()
            assertEquals(
                Move.Play(Point(0, 0)),
                client
                    .game(game)
                    .record.moves[12]
                    .move,
            )
        }

    @Test
    fun `a line whose move does not get through comes back whole, with its comment`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(failingSends(client))
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.onTap(Point(1, 0))
            h.vm.stepBack()
            h.vm.onCommentChange("hm")
            h.vm.send()
            advanceUntilIdle()
            assertEquals(
                12,
                client
                    .game(game)
                    .record.moves.size,
            )

            // Reopened, the board holds the line it was sent from, first stone armed.
            h.vm.load()
            advanceUntilIdle()
            val s = h.vm.state.value
            assertEquals(2, s.trialCount)
            assertEquals(1, s.trialMoves.size)
            assertTrue(s.canSend)
            assertEquals("hm", s.commentDraft)
            assertNotNull(s.problem)
        }

    @Test
    fun `opening a game whose move is on its way waits for it and shows the result`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val slow =
                object : TurnBasedGoServerClient by client {
                    override suspend fun sendMove(
                        id: GameId,
                        token: MoveToken,
                        move: Move,
                        comment: String?,
                    ) {
                        delay(1_000)
                        client.sendMove(id, token, move, comment)
                    }
                }
            val h = Harness(slow)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.send()

            h.vm.load()
            advanceTimeBy(100)
            assertTrue(h.vm.state.value.loading)
            advanceUntilIdle()
            val s = h.vm.state.value
            assertFalse(s.loading)
            assertEquals(14, s.moveCount)
            assertFalse(s.hasTrial)
        }

    @Test
    fun `two moves sent in quick succession both go through`() =
        runTest(dispatcher) {
            val client = FakeTurnBasedGoServerClient(latencyMillis = 0)
            val h = Harness(client)
            h.start(game)
            advanceUntilIdle()
            h.vm.onTap(Point(0, 0))
            h.vm.send()

            val other = GameId(DEMO_SERVER, "demo-13")
            val second = BoardViewModel(other, h.session, h.games, h.outbox) { }
            second.load()
            advanceUntilIdle()
            val before =
                client
                    .game(other)
                    .record.moves.size
            second.onTap(Point(0, 0))
            second.send()
            advanceUntilIdle()

            assertEquals(
                Move.Play(Point(0, 0)),
                client
                    .game(game)
                    .record.moves[12]
                    .move,
            )
            assertEquals(
                Move.Play(Point(0, 0)),
                client
                    .game(other)
                    .record.moves[before]
                    .move,
            )
            assertNull(h.outbox.failed.value)
        }

    @Test
    fun `leaving a game drops an unsent comment, but a move that comes back keeps its own`() =
        runTest(dispatcher) {
            val h = Harness(failingSends(FakeTurnBasedGoServerClient(latencyMillis = 0)))
            h.start(game)
            advanceUntilIdle()
            h.vm.startComment()
            h.vm.onCommentChange("half a thought")
            h.vm.onLeave()
            h.vm.load()
            advanceUntilIdle()
            assertEquals("", h.vm.state.value.commentDraft)
            assertFalse(h.vm.state.value.showCommentField)

            // Sent, left, not delivered: the comment that went with the move is back.
            h.vm.onTap(Point(0, 0))
            h.vm.onCommentChange("gg")
            h.vm.send()
            h.vm.onLeave()
            advanceUntilIdle()
            h.vm.load()
            advanceUntilIdle()
            assertEquals("gg", h.vm.state.value.commentDraft)
            assertTrue(h.vm.state.value.showCommentField)
            assertFalse(h.vm.state.value.writingComment)
        }

    @Test
    fun `comments a server keeps apart from the game are asked for after it loads`() =
        runTest(dispatcher) {
            var asked = 0
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0, browserSignIn = true)
            val counting =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun comments(id: GameId): List<GameComment> {
                        asked++
                        return fake.comments(id)
                    }
                }
            val h = Harness(counting)
            h.start(game)
            advanceUntilIdle()
            assertEquals(1, asked)
            assertEquals(
                listOf("Your move, Demo!"),
                h.vm.state.value.commentsAtCursor
                    .map { it.text },
            )
        }

    @Test
    fun `comments that cannot be read leave the board alone`() =
        runTest(dispatcher) {
            val fake = FakeTurnBasedGoServerClient(latencyMillis = 0, browserSignIn = true)
            val failing =
                object : TurnBasedGoServerClient by fake {
                    override suspend fun comments(id: GameId): List<GameComment> =
                        throw GoServerException.Unreachable("offline")
                }
            val h = Harness(failing)
            h.start(game)
            advanceUntilIdle()
            val s = h.vm.state.value
            assertNull(s.problem)
            assertEquals(12, s.moveCount)
            assertEquals(emptyList(), s.commentsAtCursor)
        }

    @Test
    fun `a drag walks the timeline by whole moves and stops at its ends`() =
        runTest(dispatcher) {
            val h = Harness(FakeTurnBasedGoServerClient(latencyMillis = 0))
            h.start(game)
            advanceUntilIdle()
            val vm = h.vm
            val moves = vm.state.value.moveCount
            assertTrue(moves > 3)

            // Backwards is negative, and the cursor lands exactly where it was sent.
            vm.scrubBy(-3)
            assertEquals(moves - 3, vm.state.value.cursor)
            vm.scrubBy(2)
            assertEquals(moves - 1, vm.state.value.cursor)

            // A fling past either end stops there rather than running off the timeline.
            vm.scrubBy(-500)
            assertTrue(vm.state.value.atStart)
            assertEquals(0, vm.state.value.cursor)
            vm.scrubBy(500)
            assertTrue(vm.state.value.atEnd)
            assertEquals(vm.state.value.total, vm.state.value.cursor)

            // A trial extends the timeline, and the drag walks into it like the buttons do —
            // without disturbing the variation itself.
            vm.toCurrent()
            vm.onTap(Point(0, 0))
            vm.onTap(Point(1, 0))
            assertEquals(2, vm.state.value.trialMoves.size)
            vm.scrubBy(-1)
            assertEquals(1, vm.state.value.trialMoves.size)
            assertEquals(2, vm.state.value.trialCount)
            vm.scrubBy(1)
            assertEquals(2, vm.state.value.trialMoves.size)
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
