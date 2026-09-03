package dev.turnzen.app

import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.goserver.RemainingTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormattingTest {
    @Test
    fun `remaining time keeps the server's own wording, spaced out`() {
        assertEquals("3 d 4 h", Formatting.remaining(RemainingTime("3d 4h", Duration.ofDays(3).plusHours(4))))
        assertEquals("6 h", Formatting.remaining(RemainingTime("6h", Duration.ofHours(6))))
        // A translated account keeps its own units rather than being rewritten in English.
        assertEquals("3 n 20 ó", Formatting.remaining(RemainingTime("3n 20ó", Duration.ofDays(3).plusHours(20))))
        assertEquals("–", Formatting.remaining(null))
        assertEquals("–", Formatting.remaining(RemainingTime("")))
    }

    @Test
    fun `urgency needs a duration we understood`() {
        assertTrue(Formatting.isUrgent(RemainingTime("6h", Duration.ofHours(6))))
        assertFalse(Formatting.isUrgent(RemainingTime("3d 4h", Duration.ofDays(3).plusHours(4))))
        // Unknown wording must not be shown as urgent just because it could not be parsed.
        assertFalse(Formatting.isUrgent(RemainingTime("3xy 20zz", null)))
        assertFalse(Formatting.isUrgent(null))
    }

    @Test
    fun `relative times read as a person would say them`() {
        val now = Instant.parse("2026-09-03T20:00:00Z")
        assertEquals("just now", Formatting.relative(now.minusSeconds(20), now))
        assertEquals("18 min ago", Formatting.relative(now.minusSeconds(18 * 60), now))
        assertEquals("2 h ago", Formatting.relative(now.minus(Duration.ofHours(2)), now))
        assertEquals("yesterday", Formatting.relative(now.minus(Duration.ofHours(30)), now))
        assertEquals("4 d ago", Formatting.relative(now.minus(Duration.ofDays(4)), now))
        assertEquals("–", Formatting.relative(null, now))
    }

    @Test
    fun `board size mentions handicap only when there is one`() {
        assertEquals("19x19", Formatting.boardSize(19, 0))
        assertEquals("13x13, H2", Formatting.boardSize(13, 2))
    }
}
