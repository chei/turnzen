package dev.turnzen.app

import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.LadderStandings
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.board.BoardViewModel
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.TournamentRef
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The board's side of the ladder standing: when it is asked for, and when it is not. */
class LadderStandingTest : MainDispatcherTest() {
    /** The demo server's ladder game, and one that belongs to no tournament. */
    private val ladderGame = GameId("demo-13")
    private val casualGame = GameId("demo-9")

    private class RecordingLadders(
        private val answer: LadderStanding? = LadderStanding(12, 7, youAreChallenger = true),
    ) : LadderStandings {
        var asked = 0
        val invalidated = ArrayList<String?>()

        override suspend fun standing(
            tournament: TournamentRef?,
            id: GameId,
        ): LadderStanding? {
            asked++
            return answer
        }

        override fun invalidate(tournament: TournamentRef?) {
            invalidated += tournament?.id
        }
    }

    private suspend fun open(
        id: GameId,
        ladders: LadderStandings,
    ): BoardViewModel {
        val session = sessionFor(FakeTurnBasedGoServerClient(latencyMillis = 0), InMemoryCredentialStore())
        val games = GamesStore(session)
        session.signIn(Credentials("demo", "pw"))
        games.refresh()
        val vm = BoardViewModel(id, session, games, ladders, onMoveSent = {})
        vm.load()
        return vm
    }

    @Test
    fun `the standing arrives after the sheet is already open`() =
        runTest(dispatcher) {
            val ladders = RecordingLadders()
            val vm = open(ladderGame, ladders)
            advanceUntilIdle()

            // Nothing is fetched merely by opening the game: most openings never reach the
            // sheet, and the board is on the path to playing a move.
            assertEquals(0, ladders.asked)
            assertNull(vm.state.value.ladderStanding)

            vm.onInfoOpened()
            advanceUntilIdle()

            assertEquals(1, ladders.asked)
            val standing = vm.state.value.ladderStanding
            assertEquals(12, standing?.yourRank)
            assertEquals(7, standing?.opponentRank)
            assertTrue(standing?.youAreChallenger == true)
        }

    @Test
    fun `a game in no tournament never asks`() =
        runTest(dispatcher) {
            val ladders = RecordingLadders()
            val vm = open(casualGame, ladders)
            advanceUntilIdle()
            vm.onInfoOpened()
            advanceUntilIdle()

            assertEquals(0, ladders.asked)
            assertNull(vm.state.value.ladderStanding)
        }

    @Test
    fun `reopening the sheet does not fetch again`() =
        runTest(dispatcher) {
            val ladders = RecordingLadders()
            val vm = open(ladderGame, ladders)
            advanceUntilIdle()
            repeat(4) {
                vm.onInfoOpened()
                advanceUntilIdle()
            }
            assertEquals(1, ladders.asked)
        }

    @Test
    fun `a standing that cannot be read simply leaves the row off`() =
        runTest(dispatcher) {
            val ladders = RecordingLadders(answer = null)
            val vm = open(ladderGame, ladders)
            advanceUntilIdle()
            vm.onInfoOpened()
            advanceUntilIdle()

            assertEquals(1, ladders.asked)
            assertNull(vm.state.value.ladderStanding)
            // No error anywhere: the sheet is readable, it just says nothing about the ladder.
            assertNull(vm.state.value.problem)
        }

    @Test
    fun `resigning a ladder game forgets the ladder`() =
        runTest(dispatcher) {
            // Ending a game is exactly the event that moves ranks, the user's own included.
            val ladders = RecordingLadders()
            val vm = open(ladderGame, ladders)
            advanceUntilIdle()
            vm.askResign()
            vm.confirm()
            advanceUntilIdle()

            assertEquals(listOf<String?>("1042"), ladders.invalidated)
        }
}
