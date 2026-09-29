package dev.turnzen.app

import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.common.Urgency
import dev.turnzen.goserver.RemainingTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FormattingTest {
    @Test
    fun `a display name is shown only when it says something the handle does not`() {
        assertEquals("Bernd Weber", Formatting.displayName("BerndW", " Bernd Weber "))
        assertEquals(null, Formatting.displayName("BerndW", null))
        assertEquals(null, Formatting.displayName("BerndW", "  "))
        assertEquals(null, Formatting.displayName("BerndW", "berndw"))
    }

    @Test
    fun `a rating move keeps its hundredths, which is all a game is worth`() {
        // DGS counts in ranks: one game moves a player by a fraction of a stone, and that
        // fraction is the whole of what there is to report.
        assertEquals("+0.23", Formatting.ratingPoints(0.23))
        assertEquals("−0.25", Formatting.ratingPoints(-0.25))
        assertEquals("+0.09", Formatting.ratingPoints(0.09))
        assertEquals("+1.40", Formatting.ratingPoints(1.4))
        assertEquals("±0.00", Formatting.ratingPoints(0.0))
    }

    @Test
    fun `remaining time is rendered in the app's language`() {
        assertEquals("3 d 4 h", Formatting.remaining(RemainingTime("3d 4h", 3.days + 4.hours)))
        assertEquals("6 h", Formatting.remaining(RemainingTime("6h", 6.hours)))
        assertEquals("5 d", Formatting.remaining(RemainingTime("5d", 5.days)))
        assertEquals("40 min", Formatting.remaining(RemainingTime("40m", 40.minutes)))
        // A translated account is re-rendered in English rather than shown in its own units.
        assertEquals("3 d 20 h", Formatting.remaining(RemainingTime("3n 20ó", 3.days + 20.hours)))
        // Wording we could not interpret is still shown rather than dropped.
        assertEquals("3 xy 20 zz", Formatting.remaining(RemainingTime("3xy 20zz", null)))
        assertEquals("–", Formatting.remaining(null))
        assertEquals("–", Formatting.remaining(RemainingTime("")))
    }

    @Test
    fun `urgency needs a duration we understood`() {
        assertTrue(Formatting.isUrgent(RemainingTime("6h", 6.hours)))
        assertFalse(Formatting.isUrgent(RemainingTime("3d 4h", 3.days + 4.hours)))
        // Unknown wording must not be shown as urgent just because it could not be parsed.
        assertFalse(Formatting.isUrgent(RemainingTime("3xy 20zz", null)))
        assertFalse(Formatting.isUrgent(null))
    }

    @Test
    fun `urgency steps at three days and twelve hours, matching DGS`() {
        // At or above three days: not urgent (DGS shows these white; e.g. 4d 5h).
        assertEquals(Urgency.NONE, Formatting.urgency(RemainingTime("4d 5h", 4.days + 5.hours)))
        assertEquals(Urgency.NONE, Formatting.urgency(RemainingTime("3d", 3.days)))
        // Below three days: urgent red (e.g. the 2d 13h that DGS paints red).
        assertEquals(
            Urgency.URGENT,
            Formatting.urgency(RemainingTime("2d 13h", 2.days + 13.hours)),
        )
        assertEquals(Urgency.URGENT, Formatting.urgency(RemainingTime("12h", 12.hours)))
        // Below twelve hours: critical.
        assertEquals(
            Urgency.CRITICAL,
            Formatting.urgency(RemainingTime("11h", 11.hours + 59.minutes)),
        )
        assertEquals(Urgency.CRITICAL, Formatting.urgency(RemainingTime("6h", 6.hours)))
        // Uninterpretable wording and null stay calm.
        assertEquals(Urgency.NONE, Formatting.urgency(RemainingTime("3xy 20zz", null)))
        assertEquals(Urgency.NONE, Formatting.urgency(null))
    }

    @Test
    fun `a start date is told as how long the game has run`() {
        val now = Instant.parse("2026-09-15T12:00:00Z")

        fun since(d: Duration) = Formatting.since(now.minus(d), now)

        assertEquals("just now", since(20.minutes))
        assertEquals("3 hours ago", since(3.hours))
        // Singulars are not "1 days ago".
        assertEquals("1 hour ago", since(1.hours))
        assertEquals("1 day ago", since(25.hours))
        assertEquals("13 days ago", since(13.days))
        // Days never roll over into months or years: the exact count is the useful number.
        assertEquals("432 days ago", since(432.days))
        assertEquals("1500 days ago", since(1500.days))
        assertEquals("–", Formatting.since(null, now))
    }

    @Test
    fun `relative times read as a person would say them`() {
        val now = Instant.parse("2026-09-03T20:00:00Z")
        assertEquals("just now", Formatting.relative(now - 20.seconds, now))
        assertEquals("18 min ago", Formatting.relative(now - (18 * 60).seconds, now))
        assertEquals("2 h ago", Formatting.relative(now.minus(2.hours), now))
        assertEquals("23 h ago", Formatting.relative(now.minus(23.hours), now))
        // Hours continue to 48 rather than becoming "yesterday".
        assertEquals("30 h ago", Formatting.relative(now.minus(30.hours), now))
        assertEquals("47 h ago", Formatting.relative(now.minus(47.hours), now))
        assertEquals("2 d ago", Formatting.relative(now.minus(48.hours), now))
        assertEquals("4 d ago", Formatting.relative(now.minus(4.days), now))
        assertEquals("–", Formatting.relative(null, now))
    }

    @Test
    fun `board size mentions handicap only when there is one`() {
        assertEquals("19x19", Formatting.boardSize(19, 0))
        assertEquals("13x13, H2", Formatting.boardSize(13, 2))
    }
}
