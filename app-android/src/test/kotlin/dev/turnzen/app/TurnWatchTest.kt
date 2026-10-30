package dev.turnzen.app

import dev.turnzen.app.notify.CheckInput
import dev.turnzen.app.notify.SummaryAction
import dev.turnzen.app.notify.TurnWatch
import dev.turnzen.app.notify.WatchState
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TurnWatchTest {
    private val dgs = ServerId("dgs")
    private val ogs = ServerId("ogs")
    private val now = Instant.parse("2026-09-26T12:00:00Z")

    private fun game(
        id: String,
        move: Int = 10,
        server: ServerId = dgs,
        left: Duration? = 5.days,
        lastMove: Instant = now - 60.seconds,
    ) = GameSummary(
        id = GameId(server, id),
        opponent = Opponent("opp$id", null, null),
        myColor = StoneColor.BLACK,
        boardSize = 19,
        handicap = 0,
        moveNumber = move,
        lastMoveAt = lastMove,
        remainingTime = RemainingTime("x", left),
        pendingAction = PendingAction.PLAY_MOVE,
        isMyTurn = true,
        moveToken = MoveToken("t"),
    )

    private fun input(
        dgsGames: List<GameSummary>? = emptyList(),
        ogsGames: List<GameSummary>? = null,
        failed: Set<ServerId> = emptySet(),
        signedOut: Set<ServerId> = emptySet(),
        yourMove: Boolean = true,
        lowTime: Boolean = true,
        foreground: Boolean = false,
        shown: Boolean = false,
    ) = CheckInput(
        answered =
            buildMap {
                dgsGames?.let { put(dgs, it) }
                ogsGames?.let { put(ogs, it) }
            },
        failed = failed,
        signedOut = signedOut,
        yourMoveOn = yourMove,
        lowTimeOn = lowTime,
        inForeground = foreground,
        summaryShown = shown,
        now = now,
    )

    @Test
    fun `a game newly waiting on the user alerts, listing every waiting game with the new one first`() {
        val a = game("1", lastMove = now - 10.seconds)
        val b = game("2", lastMove = now - 3600.seconds)
        val state = WatchState(known = mapOf(a.id to 10))
        val out = TurnWatch.check(state, input(listOf(a, b)))
        val post = assertIs<SummaryAction.Post>(out.summary)
        assertTrue(post.alert)
        assertEquals(listOf(b.id, a.id), post.games.map { it.id })
        assertEquals(setOf(b.id), post.flagged)
        assertEquals(mapOf(a.id to 10, b.id to 10), out.state.known)
    }

    @Test
    fun `the summary is about the one game that just came round, not about all of them`() {
        val a = game("1")
        val b = game("2")
        val c = game("3")
        // One new game among three: that one leads (its picture, its board on tap).
        assertEquals(b, SummaryAction.Post(listOf(b, a, c), setOf(b.id), alert = true).lead)
        // Two new games: about neither in particular, so the list opens.
        assertNull(SummaryAction.Post(listOf(a, b, c), setOf(a.id, b.id), alert = true).lead)
        // Nothing new: a single game still leads; several do not.
        assertEquals(a, SummaryAction.Post(listOf(a), emptySet(), alert = false).lead)
        assertNull(SummaryAction.Post(listOf(a, b), emptySet(), alert = false).lead)
    }

    @Test
    fun `nothing new does not alert, and a hidden summary stays hidden`() {
        val a = game("1")
        val state = WatchState(known = mapOf(a.id to 10))
        assertEquals(SummaryAction.Keep, TurnWatch.check(state, input(listOf(a))).summary)
    }

    @Test
    fun `nothing new updates a summary that is up without a sound`() {
        val a = game("1")
        val b = game("2")
        val state = WatchState(known = mapOf(a.id to 10, b.id to 10), flagged = setOf(b.id))
        val post = assertIs<SummaryAction.Post>(TurnWatch.check(state, input(listOf(b), shown = true)).summary)
        assertEquals(false, post.alert)
        assertEquals(listOf(b.id), post.games.map { it.id })
        assertEquals(setOf(b.id), post.flagged)
    }

    @Test
    fun `the same game coming round again after more moves is new`() {
        val a = game("1", move = 12)
        val out = TurnWatch.check(WatchState(known = mapOf(a.id to 10)), input(listOf(a)))
        assertTrue(assertIs<SummaryAction.Post>(out.summary).alert)
    }

    @Test
    fun `no games left takes the summary down`() {
        val state = WatchState(known = mapOf(GameId(dgs, "1") to 10))
        assertEquals(SummaryAction.Cancel, TurnWatch.check(state, input(emptyList(), shown = true)).summary)
    }

    @Test
    fun `a server that did not answer keeps its games known and the summary untouched`() {
        val a = GameId(ogs, "9")
        val state = WatchState(known = mapOf(a to 3))
        val out = TurnWatch.check(state, input(emptyList(), failed = setOf(ogs), shown = true))
        assertEquals(SummaryAction.Keep, out.summary)
        assertEquals(mapOf(a to 3), out.state.known)
    }

    @Test
    fun `in the foreground nothing is posted and what was seen does not alert later`() {
        val a = game("1")
        val out = TurnWatch.check(WatchState(), input(listOf(a), foreground = true))
        assertEquals(SummaryAction.Cancel, out.summary)
        val later = TurnWatch.check(out.state, input(listOf(a)))
        assertEquals(SummaryAction.Keep, later.summary)
    }

    @Test
    fun `with the switch off the summary is taken down but games are still recorded`() {
        val a = game("1")
        val out = TurnWatch.check(WatchState(), input(listOf(a), yourMove = false))
        assertEquals(SummaryAction.Cancel, out.summary)
        assertEquals(mapOf(a.id to 10), out.state.known)
    }

    @Test
    fun `opening the app clears the flags and marks the fetched games as seen`() {
        val a = game("1")
        val seen = TurnWatch.seen(WatchState(flagged = setOf(a.id)), mapOf(dgs to listOf(a)))
        assertEquals(emptySet(), seen.flagged)
        assertEquals(mapOf(a.id to 10), seen.known)
    }

    @Test
    fun `low time warns once per move, at 24 hours or less`() {
        val low = game("1", left = 24.hours)
        val fine = game("2", left = 25.hours)
        val out = TurnWatch.check(WatchState(), input(listOf(low, fine)))
        assertEquals(listOf(low.id), out.lowTimeAlerts.map { it.id })
        val again = TurnWatch.check(out.state, input(listOf(low, fine)))
        assertEquals(emptyList(), again.lowTimeAlerts)
        val nextMove = TurnWatch.check(again.state, input(listOf(low.copy(moveNumber = 12))))
        assertEquals(listOf(low.id), nextMove.lowTimeAlerts.map { it.id })
    }

    @Test
    fun `one game with a low-time notice gets no summary, as it would only repeat it`() {
        val low = game("1", left = 20.hours)
        val out = TurnWatch.check(WatchState(), input(listOf(low)))
        assertEquals(listOf(low.id), out.lowTimeAlerts.map { it.id })
        assertEquals(SummaryAction.Cancel, out.summary)
    }

    @Test
    fun `a waiting game whose clock goes low takes its summary down`() {
        val a = game("1", left = 20.hours)
        val state = WatchState(known = mapOf(a.id to 10))
        val out = TurnWatch.check(state, input(listOf(a), shown = true))
        assertEquals(listOf(a.id), out.lowTimeAlerts.map { it.id })
        assertEquals(SummaryAction.Cancel, out.summary)
    }

    @Test
    fun `one low game keeps its summary while low time is off`() {
        val low = game("1", left = 20.hours)
        val out = TurnWatch.check(WatchState(), input(listOf(low), lowTime = false))
        assertTrue(assertIs<SummaryAction.Post>(out.summary).alert)
    }

    @Test
    fun `one low game keeps its summary while another server is silent`() {
        val low = game("1", left = 20.hours)
        val out = TurnWatch.check(WatchState(), input(listOf(low), failed = setOf(ogs)))
        assertIs<SummaryAction.Post>(out.summary)
    }

    @Test
    fun `a new game that warns about its clock sounds only there`() {
        val low = game("1", left = 20.hours)
        val other = game("2")
        val state = WatchState(known = mapOf(other.id to 10))
        val out = TurnWatch.check(state, input(listOf(low, other)))
        assertEquals(listOf(low.id), out.lowTimeAlerts.map { it.id })
        val post = assertIs<SummaryAction.Post>(out.summary)
        assertEquals(false, post.alert)
        assertEquals(listOf(low.id, other.id), post.games.map { it.id })
    }

    @Test
    fun `a new game beside a low one still sounds the summary`() {
        val low = game("1", left = 20.hours)
        val fine = game("2")
        val out = TurnWatch.check(WatchState(), input(listOf(low, fine)))
        assertTrue(assertIs<SummaryAction.Post>(out.summary).alert)
    }

    @Test
    fun `a clock the app could not read is never low`() {
        val out = TurnWatch.check(WatchState(), input(listOf(game("1", left = null))))
        assertEquals(emptyList(), out.lowTimeAlerts)
        assertNull(out.nextLowTimeCheck)
    }

    @Test
    fun `the next low-time check is when the soonest clock crosses the line, never sooner than 15 minutes`() {
        val out =
            TurnWatch.check(
                WatchState(),
                input(listOf(game("1", left = 3.days), game("2", left = 2.days))),
            )
        assertEquals(now + 1.days, out.nextLowTimeCheck)
        val close = TurnWatch.check(WatchState(), input(listOf(game("3", left = 24.hours + 1.minutes))))
        assertEquals(now + 15.minutes, close.nextLowTimeCheck)
    }

    @Test
    fun `a low-time notice goes when the game is no longer the user's move`() {
        val id = GameId(dgs, "1")
        val out = TurnWatch.check(WatchState(lowAlerted = mapOf(id to 10)), input(emptyList()))
        assertEquals(setOf(id), out.lowTimeCancels)
        assertEquals(emptyMap(), out.state.lowAlerted)
    }

    @Test
    fun `low time is silent while its switch is off`() {
        val out = TurnWatch.check(WatchState(), input(listOf(game("1", left = 3.hours)), lowTime = false))
        assertEquals(emptyList(), out.lowTimeAlerts)
        assertNull(out.nextLowTimeCheck)
    }

    @Test
    fun `a sign-out is said once, and again only after signing in and out again`() {
        val first = TurnWatch.check(WatchState(), input(emptyList(), signedOut = setOf(ogs)))
        assertEquals(setOf(ogs), first.signedOutAlerts)
        val second = TurnWatch.check(first.state, input(emptyList(), signedOut = setOf(ogs)))
        assertEquals(emptySet(), second.signedOutAlerts)
        val back = TurnWatch.check(second.state, input(emptyList(), ogsGames = emptyList()))
        val third = TurnWatch.check(back.state, input(emptyList(), signedOut = setOf(ogs)))
        assertEquals(setOf(ogs), third.signedOutAlerts)
    }
}
