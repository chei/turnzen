package dev.turnzen.app.ui.common

import dev.turnzen.goserver.RemainingTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Formatting {
    /**
     * The server's own wording for the remaining time, with a thin space inserted between
     * each number and its unit so the column matches the type scale ("3d 4h" reads
     * "3 d 4 h", "3n 20ó" reads "3 n 20 ó"). The wording is never translated: it is
     * already in the account holder's language and matches what the server's own site shows.
     */
    fun remaining(time: RemainingTime?): String {
        val text = time?.text?.trim().orEmpty()
        if (text.isEmpty()) return "–"
        return text.replace(Regex("""(\d)(\p{L})"""), "$1 $2")
    }

    /** True when under a day is left. False when the wording could not be interpreted. */
    fun isUrgent(time: RemainingTime?): Boolean {
        val main = time?.main ?: return false
        return main < Duration.ofHours(24)
    }

    /** "18 min ago", "2 h ago", "yesterday", "4 d ago", "12 Aug". */
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
            elapsed.toHours() < 24 -> "${elapsed.toHours()} h ago"
            elapsed.toDays() < 2 -> "yesterday"
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
