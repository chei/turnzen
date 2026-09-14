package dev.turnzen.app.ui.common

import dev.turnzen.goserver.RemainingTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How close to timing out a game is, in three steps of the single urgency colour. */
enum class Urgency { NONE, URGENT, CRITICAL }

object Formatting {
    // DGS paints the remaining time red on its own status page once under three days; we
    // match that boundary, and add a stronger step below twelve hours for a game about to
    // time out. Both use the same urgent red (DESIGN.md: urgency is the only extra colour);
    // only the emphasis grows.
    private val URGENT_UNDER = Duration.ofDays(3)
    private val CRITICAL_UNDER = Duration.ofHours(12)

    /**
     * Remaining time in the app's own words ("3 d 20 h"). The server sends this field
     * already formatted in the account holder's language, so it is parsed back into a
     * duration and re-rendered here; the app is English-only for now and mixing the
     * server's wording into it reads as a bug. Wording the client cannot interpret is
     * shown as the server sent it, which is better than showing nothing.
     */
    fun remaining(time: RemainingTime?): String {
        if (time == null) return "–"
        val main = time.main ?: return spaced(time.text).ifBlank { "–" }
        val days = main.toDays()
        val hours = main.minusDays(days).toHours()
        val minutes = main.minusDays(days).minusHours(hours).toMinutes()
        // DGS reports remaining time only down to whole hours (as its own web UI does), so
        // the minute branch fires only if a server ever sends finer data; nothing is invented.
        return when {
            days > 0 && hours > 0 -> "$days d $hours h"
            days > 0 -> "$days d"
            hours > 0 -> "$hours h"
            minutes > 0 -> "$minutes min"
            else -> "0 h"
        }
    }

    /** Separates a number from its unit ("3n 20ó" -> "3 n 20 ó") to match the type scale. */
    private fun spaced(text: String): String = text.trim().replace(Regex("""(\d)(\p{L})"""), "$1 $2")

    /**
     * The urgency step for a game's remaining main time. Wording we could not parse into a
     * duration (a null main) is never urgent — a false alarm is worse than a plain label.
     */
    fun urgency(time: RemainingTime?): Urgency {
        val main = time?.main ?: return Urgency.NONE
        return when {
            main < CRITICAL_UNDER -> Urgency.CRITICAL
            main < URGENT_UNDER -> Urgency.URGENT
            else -> Urgency.NONE
        }
    }

    /** True once the time is worth colouring at all (under three days, matching DGS). */
    fun isUrgent(time: RemainingTime?): Boolean = urgency(time) != Urgency.NONE

    /**
     * "18 min ago", "2 h ago", "36 h ago", "4 d ago", "12 Aug".
     *
     * Hours run all the way to 48 rather than switching to a word like "yesterday": it is
     * exact, carries more information, and avoids implying a calendar day (the elapsed
     * time here is not aligned to the local date, and the source timestamps are UTC).
     */
    fun relative(
        instant: Instant?,
        now: Instant = Instant.now(),
    ): String {
        if (instant == null) return "–"
        val elapsed = Duration.between(instant, now)
        val minutes = elapsed.toMinutes()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            elapsed.toHours() < 48 -> "${elapsed.toHours()} h ago"
            elapsed.toDays() < 14 -> "${elapsed.toDays()} d ago"
            else -> DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(instant)
        }
    }

    /** An absolute date for something that happened long ago ("24 Jun 2015"). */
    fun date(instant: Instant?): String =
        if (instant == null) {
            "–"
        } else {
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(instant)
        }

    /** "6.5", and "6" rather than "6.0" — for komi, margins and point totals alike. */
    fun points(value: Double): String =
        if (value ==
            value.toLong().toDouble()
        ) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    fun boardSize(
        size: Int,
        handicap: Int,
    ): String = if (handicap > 0) "${size}x$size, H$handicap" else "${size}x$size"

    fun opponent(
        handle: String,
        rank: String?,
    ): String = if (rank.isNullOrBlank()) handle else "$handle ($rank)"
}
