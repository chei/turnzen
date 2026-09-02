package dev.turnzen.app.ui.common

import dev.turnzen.goserver.RemainingTime
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Formatting {
    /** "3 d 4 h", "6 h", "5 d". Falls back to the server's extra-time text when the main time is gone. */
    fun remaining(time: RemainingTime?): String {
        if (time == null) return "–"
        val main = time.main
        if (main.isZero || main.isNegative) {
            return time.extra
                ?.trim()
                ?.removePrefix("+")
                ?.trim() ?: "0 h"
        }
        val days = main.toDays()
        val hours = main.minusDays(days).toHours()
        return when {
            days > 0 && hours > 0 -> "$days d $hours h"
            days > 0 -> "$days d"
            hours > 0 -> "$hours h"
            else -> "< 1 h"
        }
    }

    fun isUrgent(time: RemainingTime?): Boolean = time != null && time.main < Duration.ofHours(24)

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
