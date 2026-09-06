package dev.turnzen.app.ui.common

import dev.turnzen.goserver.RemainingTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Formatting {
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

    /** True when under a day is left. False when the wording could not be interpreted. */
    fun isUrgent(time: RemainingTime?): Boolean {
        val main = time?.main ?: return false
        return main < Duration.ofHours(24)
    }

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

    fun boardSize(
        size: Int,
        handicap: Int,
    ): String = if (handicap > 0) "${size}x$size, H$handicap" else "${size}x$size"

    fun opponent(
        handle: String,
        rank: String?,
    ): String = if (rank.isNullOrBlank()) handle else "$handle ($rank)"
}
