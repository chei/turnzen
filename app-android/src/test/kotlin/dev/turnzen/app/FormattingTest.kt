package dev.turnzen.app

import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Urgency
import dev.turnzen.goserver.RemainingTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormattingTest {
    @Test
    fun `remaining time is rendered in the app's language`() {
        assertEquals("3 d 4 h", Formatting.remaining(RemainingTime("3d 4h", Duration.ofDays(3).plusHours(4))))
        assertEquals("6 h", Formatting.remaining(RemainingTime("6h", Duration.ofHours(6))))
        assertEquals("5 d", Formatting.remaining(RemainingTime("5d", Duration.ofDays(5))))
        assertEquals("40 min", Formatting.remaining(RemainingTime("40m", Duration.ofMinutes(40))))
        // A translated account is re-rendered in English rather than shown in its own units.
        assertEquals("3 d 20 h", Formatting.remaining(RemainingTime("3n 20ó", Duration.ofDays(3).plusHours(20))))
        // Wording we could not interpret is still shown rather than dropped.
        assertEquals("3 xy 20 zz", Formatting.remaining(RemainingTime("3xy 20zz", null)))
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
    fun `urgency steps at three days and twelve hours, matching DGS`() {
        // At or above three days: not urgent (DGS shows these white; e.g. 4d 5h).
        assertEquals(Urgency.NONE, Formatting.urgency(RemainingTime("4d 5h", Duration.ofDays(4).plusHours(5))))
        assertEquals(Urgency.NONE, Formatting.urgency(RemainingTime("3d", Duration.ofDays(3))))
        // Below three days: urgent red (e.g. the 2d 13h that DGS paints red).
        assertEquals(
            Urgency.URGENT,
            Formatting.urgency(RemainingTime("2d 13h", Duration.ofDays(2).plusHours(13))),
        )
        assertEquals(Urgency.URGENT, Formatting.urgency(RemainingTime("12h", Duration.ofHours(12))))
        // Below twelve hours: critical.
        assertEquals(
            Urgency.CRITICAL,
            Formatting.urgency(RemainingTime("11h", Duration.ofHours(11).plusMinutes(59))),
        )
        assertEquals(Urgency.CRITICAL, Formatting.urgency(RemainingTime("6h", Duration.ofHours(6))))
        // Uninterpretable wording and null stay calm.
        assertEquals(Urgency.NONE, Formatting.urgency(RemainingTime("3xy 20zz", null)))
        assertEquals(Urgency.NONE, Formatting.urgency(null))
    }

    @Test
    fun `a start date is told as how long the game has run`() {
        val now = Instant.parse("2026-09-15T12:00:00Z")

        fun since(d: Duration) = Formatting.since(now.minus(d), now)

        assertEquals("just now", since(Duration.ofMinutes(20)))
        assertEquals("3 hours ago", since(Duration.ofHours(3)))
        // Singulars are not "1 days ago".
        assertEquals("1 hour ago", since(Duration.ofHours(1)))
        assertEquals("1 day ago", since(Duration.ofHours(25)))
        assertEquals("13 days ago", since(Duration.ofDays(13)))
        assertEquals("59 days ago", since(Duration.ofDays(59)))
        // Two months in, days stop being informative.
        assertEquals("2 months ago", since(Duration.ofDays(60)))
        assertEquals("12 months ago", since(Duration.ofDays(365)))
        // Only past a year and a half, where "1 year" would round away less than it tells.
        assertEquals("1 year ago", since(Duration.ofDays(547)))
        assertEquals("4 years ago", since(Duration.ofDays(1500)))
        assertEquals("–", Formatting.since(null, now))
    }

    @Test
    fun `relative times read as a person would say them`() {
        val now = Instant.parse("2026-09-03T20:00:00Z")
        assertEquals("just now", Formatting.relative(now.minusSeconds(20), now))
        assertEquals("18 min ago", Formatting.relative(now.minusSeconds(18 * 60), now))
        assertEquals("2 h ago", Formatting.relative(now.minus(Duration.ofHours(2)), now))
        assertEquals("23 h ago", Formatting.relative(now.minus(Duration.ofHours(23)), now))
        // Hours continue to 48 rather than becoming "yesterday".
        assertEquals("30 h ago", Formatting.relative(now.minus(Duration.ofHours(30)), now))
        assertEquals("47 h ago", Formatting.relative(now.minus(Duration.ofHours(47)), now))
        assertEquals("2 d ago", Formatting.relative(now.minus(Duration.ofHours(48)), now))
        assertEquals("4 d ago", Formatting.relative(now.minus(Duration.ofDays(4)), now))
        assertEquals("–", Formatting.relative(null, now))
    }

    @Test
    fun `board size mentions handicap only when there is one`() {
        assertEquals("19x19", Formatting.boardSize(19, 0))
        assertEquals("13x13, H2", Formatting.boardSize(13, 2))
    }
}
