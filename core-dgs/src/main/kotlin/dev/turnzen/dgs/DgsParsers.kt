package dev.turnzen.dgs

import dev.turnzen.dgs.QuickSuite.array
import dev.turnzen.dgs.QuickSuite.int
import dev.turnzen.dgs.QuickSuite.obj
import dev.turnzen.dgs.QuickSuite.string
import dev.turnzen.game.GameResult
import dev.turnzen.game.Point
import dev.turnzen.game.ResultReason
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfCoordinates
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameInfo
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.Opponent
import dev.turnzen.goserver.PendingAction
import dev.turnzen.goserver.PlayerDetail
import dev.turnzen.goserver.RemainingTime
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TournamentRef
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

    /**
     * What a finished game did to one player's rating: DGS's own `rating_diff`, out of the
     * `black_gameinfo` / `white_gameinfo` block, and only when the request asked for
     * `with=ratingdiff`. The unit is ranks — "+0.23" is not quite a quarter of a stone.
     *
     * Deliberately not the difference between `rating_start` and `rating_end`. Those are the
     * ranks the player held at the two ends of *this game*, and a correspondence game runs
     * for months: the gap between them is mostly other games, and it is there for an unrated
     * game too, where this game moved nothing at all.
     *
     * Null when the game was not rated, which is the same answer as "nothing to say": an
     * unrated game earns no line rather than a "+0.00" that reads like a rounding error.
     */
    fun change(
        info: JsonObject?,
        rated: Boolean,
    ): Double? {
        if (info == null || !rated) return null
        return info.string("rating_diff")?.trim()?.toDoubleOrNull()
    }
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

/** DGS ruleset names, as the counting rule they imply. */
internal object DgsRulesets {
    /** DGS ships Japanese and Chinese rules; anything unexpected is counted as territory. */
    fun scoring(value: String?): ScoringRules? =
        when (value?.trim()?.uppercase()) {
            null, "" -> null
            "CHINESE" -> ScoringRules.AREA
            else -> ScoringRules.TERRITORY
        }
}

/**
 * A finished game's score line: "0" for a draw, "VOID" for no result, otherwise the winner
 * and either a margin in points or the letter for how they won ("W+7", "B+2.5", "W+R",
 * "B+T", "W+F").
 */
internal object DgsScore {
    fun parse(value: String?): GameResult? {
        val text = value?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text == "0") return GameResult(null, 0.0, ResultReason.JIGO)
        if (text.equals("VOID", ignoreCase = true)) return GameResult(null, null, ResultReason.NO_RESULT)
        val winner =
            when (text.firstOrNull()?.uppercaseChar()) {
                'B' -> StoneColor.BLACK
                'W' -> StoneColor.WHITE
                else -> return null
            }
        val by = text.substringAfter('+', "").trim()
        return when (by.uppercase()) {
            "R" -> GameResult(winner, null, ResultReason.RESIGNATION)
            "T" -> GameResult(winner, null, ResultReason.TIMEOUT)
            "F" -> GameResult(winner, null, ResultReason.FORFEIT)
            else -> by.toDoubleOrNull()?.let { GameResult(winner, it, ResultReason.POINTS) }
        }
    }
}

/**
 * The answer to `status_score` and `score`: the state of every point on a board being
 * counted. Coordinates come back in SGF letters, comma-separated when the client asks for
 * `fmt=,` and run together otherwise, so both are accepted.
 */
internal object ScoreStateParser {
    fun parse(envelope: JsonObject): ScoreState =
        ScoreState(
            result = DgsScore.parse(envelope.string("score")),
            rules = DgsRulesets.scoring(envelope.string("ruleset")),
            deadStones = points(envelope.string("black_dead")) + points(envelope.string("white_dead")),
            blackTerritory = points(envelope.string("black_territory")),
            whiteTerritory = points(envelope.string("white_territory")),
            // "neutral" points are switchable dame; both are nobody's for counting purposes.
            dame = points(envelope.string("dame")) + points(envelope.string("neutral")),
        )

    /** "bc,ef,nj" or "bcefnj" — a run of two-letter SGF points either way. */
    fun points(value: String?): Set<Point> {
        val letters = value?.filter { it in 'a'..'z' } ?: return emptySet()
        if (letters.length % 2 != 0) {
            throw GoServerException.Protocol("Odd coordinate list from DGS: ${value.take(40)}")
        }
        return letters.chunked(2).map { SgfCoordinates.decodePoint(it) }.toSet()
    }

    /** The same encoding, for the points a client asks the server to toggle. */
    fun encode(points: Collection<Point>): String = points.joinToString(",") { SgfCoordinates.encode(it) }
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
            scoring = DgsRulesets.scoring(game.string("ruleset")),
            rated = game.int("rated")?.let { it != 0 },
            timeLimit = game.string("time_limit")?.ifBlank { null },
            weekendClock = game.int("time_weekend_clock")?.let { it != 0 },
            startedAt = DgsDates.parse(game.string("time_started")),
            // DGS writes 0 for "not a tournament game", and the id is all the game object
            // carries — the name and the kind come from the SGF, see [DgsTournament].
            tournament =
                game
                    .string("tournament_id")
                    ?.takeUnless { it.isBlank() || it == "0" }
                    ?.let { TournamentRef(id = it) },
        )

    private fun player(
        game: JsonObject,
        color: String,
    ): PlayerDetail {
        val user = game.obj("${color}_user")
        val standing = game.obj("${color}_gameinfo")
        val finished = game.string("status").equals("FINISHED", ignoreCase = true)
        return PlayerDetail(
            handle = user?.string("handle") ?: "?",
            displayName = user?.string("name")?.ifBlank { null },
            rank = DgsRatings.rank(user?.string("rating")),
            country = user?.string("country")?.ifBlank { null },
            rankAtStart = DgsRatings.rank(standing?.string("rating_start")),
            prisoners = standing?.int("prisoners"),
            // Both clocks, unlike the summary's, which only carries the user's own.
            remainingTime = DgsTimeRemaining.parse(standing?.string("remtime")),
            ratingChange =
                if (finished) {
                    DgsRatings.change(standing, rated = game.int("rated")?.let { it != 0 } ?: false)
                } else {
                    null
                },
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

/**
 * The tournament a game belongs to, read out of the SGF rather than the game object.
 *
 * The QuickSuite game object only carries `tournament_id`; there is no tournament object in
 * the QuickSuite at all. But the SGF DGS serves for every game — which this client downloads
 * anyway, for the moves — carries the event in its standard SGF header:
 *
 * ```
 * EV[(Dragon Ladder) Tournament #3: DGS Ladder (19x19)]
 * RO[Round 1 of 1]
 * ```
 *
 * The bracketed prefix is the kind (scope plus format: "Dragon Ladder", "Private Round
 * Robin"), so it is also how a ladder game is told apart from a bracket or league game.
 * Both properties are absent for a casual game.
 *
 * Parsed loosely on purpose: DGS translates some of what it sends into the account's
 * language, and it is not established that the SGF header is exempt. Anything that cannot be
 * read is dropped rather than failing the game load — none of it is needed to play a move.
 */
internal object DgsTournament {
    private val EVENT = Regex("""^\s*(?:\(([^)]*)\))?\s*[^#]*#\s*(\d+)\s*:?\s*(.*)$""")
    private val ROUND = Regex("""(\d+)\D+(\d+)""")

    /** Merges what the SGF header adds onto what the game object already gave us. */
    fun merge(
        fromGameObject: TournamentRef?,
        sgfRoot: Map<String, List<String>>,
    ): TournamentRef? {
        val event = parseEvent(sgfRoot["EV"]?.firstOrNull())
        val id = fromGameObject?.id ?: event?.id ?: return null
        val round = ROUND.find(sgfRoot["RO"]?.firstOrNull().orEmpty())
        return TournamentRef(
            id = id,
            name = event?.name,
            kind = event?.kind,
            isLadder = event?.isLadder ?: false,
            round = round?.groupValues?.get(1)?.toIntOrNull(),
            roundCount = round?.groupValues?.get(2)?.toIntOrNull(),
        )
    }

    private fun parseEvent(event: String?): TournamentRef? {
        val match = EVENT.find(event?.trim().orEmpty()) ?: return null
        val (kind, id, name) = match.destructured
        return TournamentRef(
            id = id,
            name = name.trim().ifEmpty { null },
            kind = kind.trim().ifEmpty { null },
            // The kind goes through DGS's translation, whose data is partial: it arrives in
            // English on many accounts whose UI is not. A wording we cannot read simply leaves
            // the flag false, and the kind is still shown exactly as it came.
            isLadder = kind.contains("ladder", ignoreCase = true),
        )
    }
}

/** Turns one game object (from `cmd=info` or a `cmd=list` entry) into a [GameSummary]. */
internal object GameObjectParser {
    fun parse(
        game: JsonObject,
        myUserId: String?,
    ): GameSummary {
        val id = game.string("id") ?: throw GoServerException.Protocol("Game without id")
        val status = game.string("status")
        val finished = status.equals("FINISHED", ignoreCase = true)
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
        // Whose turn it is: prefer the explicit move_uid, fall back to the move color. A
        // finished game is nobody's turn, and DGS omits both fields for one, so it is settled
        // here rather than read out of fields that are not there.
        val myTurn =
            when {
                finished -> false
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
            pendingAction = action(game.int("game_action"), status),
            isMyTurn = myTurn,
            // A finished game has no move to make, so a missing token is not a protocol
            // failure there — the whole point of the entry is that the game is over.
            moveToken =
                MoveToken(
                    game.string("move_id")
                        ?: if (finished) "" else throw GoServerException.Protocol("Game $id without move_id"),
                ),
            isFinished = finished,
            result = DgsScore.parse(game.string("score")),
            // Only ever sent for a finished game, and only when the request asked for it.
            ratingChange =
                if (finished) {
                    DgsRatings.change(myInfo, rated = game.int("rated")?.let { it != 0 } ?: false)
                } else {
                    null
                },
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
