package dev.turnzen.dgs

import dev.turnzen.dgs.QuickSuite.array
import dev.turnzen.dgs.QuickSuite.int
import dev.turnzen.dgs.QuickSuite.obj
import dev.turnzen.dgs.QuickSuite.string
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameInfo
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.PlayerDetail
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.SignedInUser
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
 * DGS date-times are "YYYY-MM-DD hh:mm:ss" with no offset, rendered in the *user's DGS
 * profile time zone*, which the QuickSuite does not expose. We interpret them in the
 * device's own zone ([zone]): a real user's phone is set to where they live, which is the
 * time zone they also chose on DGS, so this reads correctly for them. (Interpreting the
 * bare string as UTC instead shifts every value by the user's offset, which for a UTC+2
 * user made a move from minutes ago look hours in the future — hence "just now" everywhere.)
 * The rare user whose DGS profile zone differs from their phone is still off by that gap.
 *
 * A trailing "GMT" (not seen in practice, but cheap to honour) means the value really is
 * UTC and is read as such.
 */
internal object DgsDates {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(
        text: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        val trimmed = text?.trim() ?: return null
        val isGmt = trimmed.endsWith("GMT")
        val v = trimmed.removeSuffix("GMT").trim()
        if (v.isEmpty()) return null
        return try {
            val local = LocalDateTime.parse(v, formatter)
            if (isGmt) local.toInstant(ZoneOffset.UTC) else local.atZone(zone).toInstant()
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

/**
 * The read-only context of a game — its settings and both players' standing — from the same
 * object [GameObjectParser] reads the summary out of. Nothing here is needed to play a move,
 * so every field is optional and a missing one simply goes unshown.
 */
internal object GameInfoParser {
    fun parse(game: JsonObject): GameInfo =
        GameInfo(
            black = player(game, "black"),
            white = player(game, "white"),
            komi = game.string("komi")?.trim()?.toDoubleOrNull(),
            handicap = game.int("handicap") ?: 0,
            ruleset = sentenceCase(game.string("ruleset")),
            rated = game.int("rated")?.let { it != 0 },
            timeLimit = game.string("time_limit")?.ifBlank { null },
            weekendClock = game.int("time_weekend_clock")?.let { it != 0 },
            startedAt = DgsDates.parse(game.string("time_started")),
            // DGS writes 0 for "not a tournament game".
            tournamentId = game.string("tournament_id")?.takeUnless { it.isBlank() || it == "0" },
        )

    private fun player(
        game: JsonObject,
        color: String,
    ): PlayerDetail {
        val user = game.obj("${color}_user")
        val standing = game.obj("${color}_gameinfo")
        return PlayerDetail(
            handle = user?.string("handle") ?: "?",
            displayName = user?.string("name")?.ifBlank { null },
            rank = DgsRatings.rank(user?.string("rating")),
            country = user?.string("country")?.ifBlank { null },
            rankAtStart = DgsRatings.rank(standing?.string("rating_start")),
            prisoners = standing?.int("prisoners"),
            // Both clocks, unlike the summary's, which only carries the user's own.
            remainingTime = DgsTimeRemaining.parse(standing?.string("remtime")),
        )
    }

    /** DGS shouts its enum values ("JAPANESE"); Turnzen is sentence case throughout. */
    private fun sentenceCase(value: String?): String? =
        value
            ?.trim()
            ?.ifEmpty { null }
            ?.lowercase()
            ?.replaceFirstChar { it.uppercase() }
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
        // Whose turn it is: prefer the explicit move_uid, fall back to the move color.
        val myTurn =
            when {
                me != null && game.string("move_uid") != null -> game.string("move_uid") == me
                else -> parseColor(game.string("move_color")) == myColor
            }
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
            isMyTurn = myTurn,
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
