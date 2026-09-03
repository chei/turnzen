package dev.turnzen.dgs

import dev.turnzen.dgs.QuickSuite.array
import dev.turnzen.dgs.QuickSuite.int
import dev.turnzen.dgs.QuickSuite.obj
import dev.turnzen.dgs.QuickSuite.string
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.SignedInUser
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Plain-text answer of `login.php?quick_mode=1`. */
internal object LoginResponse {
    private val errorPattern = Regex("""\[#Error:\s*([A-Za-z0-9_]+)\s*;([^\]]*)]""")

    /** Returns null on success, otherwise the exception matching the error line. */
    fun failure(body: String): GoServerException? {
        val match = errorPattern.find(body) ?: return null
        return QuickSuite.exceptionFor(match.groupValues[1], match.groupValues[2].trim())
    }
}

/**
 * DGS date-times are "YYYY-MM-DD hh:mm:ss" in the *user's profile time zone*, which the
 * QuickSuite does not expose. They are read as UTC here; ordering is exact, absolute
 * values may be off by the user's DGS time-zone offset.
 */
internal object DgsDates {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(text: String?): Instant? {
        val v = text?.trim()?.removeSuffix("GMT")?.trim() ?: return null
        if (v.isEmpty()) return null
        return try {
            LocalDateTime.parse(v, formatter).toInstant(ZoneOffset.UTC)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/**
 * Remaining time, e.g. "J: 83d 14h (+ 1d * 10)", "C: 1d 10h (+ 3d / 5)", "F: 8d (+ 12h)".
 *
 * DGS translates this field into the account's language while leaving neighbouring fields
 * (such as `time_limit`) in English, so a Hungarian account reports "F: 3n 20ó (+ 1n 6ó)".
 * The server's wording is kept verbatim for display; the duration is only derived when the
 * units are recognised, and drives nothing but the "running out" highlight.
 */
internal object DgsTimeRemaining {
    /**
     * Unit abbreviations per language. Anything absent here still displays correctly, it
     * just yields no duration. Add a language by adding its three abbreviations.
     */
    private val units: Map<String, Duration> =
        mapOf(
            // English
            "d" to Duration.ofDays(1),
            "h" to Duration.ofHours(1),
            "m" to Duration.ofMinutes(1),
            // Hungarian: nap, óra, perc
            "n" to Duration.ofDays(1),
            "ó" to Duration.ofHours(1),
            "p" to Duration.ofMinutes(1),
        )

    private val part = Regex("""(\d+)\s*(\p{L}+)""")

    fun parse(text: String?): RemainingTime? {
        val raw = text?.trim() ?: return null
        if (raw.isEmpty()) return null
        val withoutType = raw.substringAfter(": ", raw)
        val mainText = withoutType.substringBefore('(').trim()
        val extraText =
            withoutType
                .substringAfter('(', "")
                .substringBefore(')')
                .trim()
                .ifEmpty { null }
        if (mainText.isEmpty() && extraText == null) return null
        return RemainingTime(text = mainText, main = duration(mainText), extra = extraText)
    }

    /** Null unless every "<number><unit>" pair uses a unit this client knows. */
    private fun duration(mainText: String): Duration? {
        val matches = part.findAll(mainText).toList()
        if (matches.isEmpty()) return null
        var total = Duration.ZERO
        for (m in matches) {
            val unit = units[m.groupValues[2].lowercase()] ?: return null
            total += unit.multipliedBy(m.groupValues[1].toLong())
        }
        return total
    }
}

internal object DgsRatings {
    /** "3k (-7%)" -> "3k"; "" -> null. */
    fun rank(rating: String?): String? = rating?.trim()?.substringBefore(' ')?.ifEmpty { null }
}

internal object UserInfoParser {
    fun parse(obj: JsonObject): SignedInUser =
        SignedInUser(
            id = obj.string("id") ?: throw GoServerException.Protocol("User info without id"),
            handle = obj.string("handle") ?: throw GoServerException.Protocol("User info without handle"),
            displayName = obj.string("name")?.ifBlank { null },
            rank = DgsRatings.rank(obj.string("rating")),
        )
}

/** Turns one game object (from `cmd=info` or a `cmd=list` entry) into a [GameSummary]. */
internal object GameObjectParser {
    fun parse(
        game: JsonObject,
        myUserId: String?,
    ): GameSummary {
        val id = game.string("id") ?: throw GoServerException.Protocol("Game without id")
        val black = game.obj("black_user")
        val white = game.obj("white_user")
        val me = myUserId ?: game.string("my_id")
        val blackId = black?.string("id")
        val myColor =
            when {
                me != null && blackId == me -> {
                    StoneColor.BLACK
                }

                me != null && white?.string("id") == me -> {
                    StoneColor.WHITE
                }

                else -> {
                    parseColor(game.string("move_color"))
                        ?: throw GoServerException.Protocol("Cannot determine own color in game $id")
                }
            }
        val opponentUser = if (myColor == StoneColor.BLACK) white else black
        val myInfo = game.obj(if (myColor == StoneColor.BLACK) "black_gameinfo" else "white_gameinfo")
        return GameSummary(
            id = GameId(id),
            opponent =
                Opponent(
                    handle = opponentUser?.string("handle") ?: "?",
                    displayName = opponentUser?.string("name")?.ifBlank { null },
                    rank = DgsRatings.rank(opponentUser?.string("rating")),
                ),
            myColor = myColor,
            boardSize = game.int("size") ?: throw GoServerException.Protocol("Game $id without size"),
            handicap = game.int("handicap") ?: 0,
            moveNumber = game.int("move_count") ?: game.int("move_id") ?: 0,
            lastMoveAt = DgsDates.parse(game.string("time_lastmove")),
            remainingTime = DgsTimeRemaining.parse(myInfo?.string("remtime")),
            pendingAction = action(game.int("game_action"), game.string("status")),
            moveToken =
                MoveToken(
                    game.string("move_id") ?: throw GoServerException.Protocol("Game $id without move_id"),
                ),
        )
    }

    fun parseList(
        envelope: JsonObject,
        myUserId: String?,
    ): List<GameSummary> {
        val items = envelope.array("list_result") ?: return emptyList()
        return items.map { element ->
            val game =
                element as? JsonObject
                    ?: throw GoServerException.Protocol("Game list entry is not an object (use lstyle=json)")
            parse(game, myUserId)
        }
    }

    private fun parseColor(value: String?): StoneColor? =
        when (value?.trim()?.uppercase()) {
            "B" -> StoneColor.BLACK
            "W" -> StoneColor.WHITE
            else -> null
        }

    private fun action(
        code: Int?,
        status: String?,
    ): PendingAction =
        when (code) {
            1 -> {
                PendingAction.PLACE_HANDICAP
            }

            2 -> {
                PendingAction.PLAY_MOVE
            }

            3 -> {
                PendingAction.SCORE
            }

            10, 11, 12, 13 -> {
                PendingAction.NEGOTIATE_KOMI
            }

            else -> {
                when (status?.uppercase()) {
                    "PLAY", "PASS" -> PendingAction.PLAY_MOVE
                    "SCORE", "SCORE2" -> PendingAction.SCORE
                    "KOMI" -> PendingAction.NEGOTIATE_KOMI
                    else -> PendingAction.OTHER
                }
            }
        }
}
