package dev.turnzen.ogs

import dev.turnzen.game.BoardSizes
import dev.turnzen.game.ColoredMove
import dev.turnzen.game.GameRecord
import dev.turnzen.game.GameResult
import dev.turnzen.game.Move
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.Point
import dev.turnzen.game.ResultReason
import dev.turnzen.game.ScoringRules
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A player's avatar. OGS sends one with every full player object: an uploaded picture on its
 * own image host, or a Gravatar (which falls back to a generated pattern), both at 32 px.
 */
internal object OgsIcons {
    /** Pixels to ask Gravatar for: a list row draws the icon at 36 dp, ~108 px on a dense screen. */
    private const val SIZE = 128

    private val gravatarSize = Regex("""([?&])s=\d+""")

    /**
     * The icon of [player], if any. Gravatar draws any size on request, so its URL is asked
     * for a sharper one; an uploaded picture is left as sent, since only some sizes of it exist.
     */
    fun url(player: JsonObject?): String? {
        val icon = player?.str("icon")?.takeIf { it.startsWith("https://") } ?: return null
        return if ("gravatar.com/" in icon) icon.replace(gravatarSize, "$1s=$SIZE") else icon
    }
}

/**
 * OGS ranks. The server keeps a Glicko-2 rating; the rank shown for it is derived, the same
 * way the OGS website derives it: `ranking = ln(rating / 525) × 23.15`, where 30 is 1 dan.
 */
internal object OgsRanks {
    /** A rating deviation this high means the server is not sure yet; the site shows "?". */
    private const val PROVISIONAL_DEVIATION = 160.0

    fun ranking(rating: Double): Double = ln(rating / 525.0) * 23.15

    /** "6k", "2d"; "?" while provisional. Whole ranks, as the site shows them by default. */
    fun label(
        ranking: Double?,
        provisional: Boolean,
    ): String? {
        if (ranking == null) return null
        if (provisional) return "?"
        return if (ranking < 30) "${ceil(30 - ranking).toInt()}k" else "${floor(ranking - 29).toInt()}d"
    }

    /**
     * The rank of a player object, whichever of its shapes OGS sent: a full one with
     * `ratings.overall`, or the short one inside game data with only `rank` / `ranking`.
     */
    fun ofPlayer(player: JsonObject?): String? {
        if (player == null) return null
        val overall = player.obj("ratings")?.obj("overall")
        val rating = overall?.double("rating")
        val deviation = overall?.double("deviation")
        val provisional =
            (deviation != null && deviation >= PROVISIONAL_DEVIATION) ||
                player.str("ui_class")?.contains("provisional") == true
        val ranking = rating?.let(::ranking) ?: player.double("ranking") ?: player.double("rank")
        return label(ranking, provisional)
    }
}

/**
 * Remaining thinking time, from OGS's raw clock. OGS sends no formatted text (unlike DGS):
 * epoch milliseconds for the last move, per-player seconds for what is left, and the time
 * system's parameters. The app formats the duration in its own words, for both servers.
 */
internal object OgsClock {
    fun remaining(
        clock: JsonObject?,
        timeControl: JsonObject?,
        playerId: Long,
        now: Instant,
        paused: Boolean,
    ): RemainingTime? {
        if (clock == null || timeControl == null) return null
        val system = timeControl.str("system") ?: timeControl.str("time_control") ?: return null
        if (system == "none") return null
        val side = if (clock.long("black_player_id") == playerId) "black_time" else "white_time"
        val time = clock[side]
        val elapsed = elapsed(clock, playerId, now, paused)
        val player = time as? JsonObject
        val mainMillis =
            when (system) {
                "simple" -> (timeControl.long("per_move") ?: return null) * 1000 - elapsed
                else -> ((player?.double("thinking_time") ?: return null) * 1000).toLong() - elapsed
            }
        var main = mainMillis
        val extra =
            when (system) {
                "fischer" -> {
                    timeControl.long("time_increment")?.let { "+ ${compact(it.seconds)}" }
                }

                "byoyomi" -> {
                    val period = timeControl.long("period_time") ?: player?.long("period_time")
                    var periods = player?.int("periods") ?: timeControl.int("periods") ?: 0
                    if (main < 0 && period != null && period > 0) {
                        // Main time is gone: the overrun eats whole periods.
                        val used = (-main / (period * 1000)).toInt()
                        periods = (periods - used).coerceAtLeast(0)
                        main = 0
                    }
                    period?.let { "+ $periods × ${compact(it.seconds)}" }
                }

                "canadian" -> {
                    val period = timeControl.long("period_time")
                    val stones = timeControl.int("stones_per_period")
                    if (period != null && stones != null) "+ ${compact(period.seconds)} / $stones" else null
                }

                else -> {
                    null
                }
            }
        val duration = main.coerceAtLeast(0).milliseconds
        return RemainingTime(text = compact(duration), main = duration, extra = extra)
    }

    /**
     * How long the player has been thinking on the current move: nothing unless it is their
     * turn, and never past the moment the clock was paused (weekends, vacation).
     */
    private fun elapsed(
        clock: JsonObject,
        playerId: Long,
        now: Instant,
        paused: Boolean,
    ): Long {
        if (clock.long("current_player") != playerId) return 0
        val lastMove = clock.long("last_move") ?: return 0
        // `paused_since` can outlive the pause it describes, so it only counts while the game
        // says it is paused (its `pause_control` is not empty).
        val pausedSince = clock.long("paused_since")
        val until = if (paused && pausedSince != null) pausedSince else now.toEpochMilliseconds()
        return (until - lastMove).coerceAtLeast(0)
    }

    /** "3d 4h", "6h", "25m": a fallback text only; the app renders [RemainingTime.main] itself. */
    fun compact(duration: Duration): String {
        val days = duration.inWholeDays
        val hours = duration.inWholeHours % 24
        val minutes = duration.inWholeMinutes % 60
        return when {
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}

/** OGS's rule set names, for display and for how they count. */
internal object OgsRules {
    fun display(rules: String?): String? =
        when (rules?.lowercase()) {
            null -> null
            "aga" -> "AGA"
            "nz" -> "New Zealand"
            "ing" -> "Ing"
            else -> rules.replaceFirstChar { it.uppercase() }
        }

    fun scoring(rules: String?): ScoringRules? =
        when (rules?.lowercase()) {
            "japanese", "korean" -> ScoringRules.TERRITORY
            "chinese", "aga", "nz", "ing" -> ScoringRules.AREA
            else -> null
        }
}

/** The outcome of a finished game, from the words OGS uses for it. */
internal object OgsOutcome {
    fun result(
        outcome: String?,
        blackLost: Boolean?,
        whiteLost: Boolean?,
    ): GameResult? {
        val text = outcome?.trim()?.lowercase() ?: return null
        // An annulled game (OGS annuls early timeouts, for one) still has its winner; it just
        // does not count for rating. Only a cancelled game has no result.
        if (text == "cancellation") return GameResult(null, by = ResultReason.NO_RESULT)
        val winner =
            when {
                blackLost == true && whiteLost != true -> StoneColor.WHITE
                whiteLost == true && blackLost != true -> StoneColor.BLACK
                else -> null
            }
        val points =
            Regex("""^([0-9]+(?:\.[0-9]+)?) points?$""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
        return when {
            points != null && points == 0.0 -> GameResult(null, 0.0, ResultReason.JIGO)

            points != null -> GameResult(winner, points, ResultReason.POINTS)

            text == "resignation" -> GameResult(winner, by = ResultReason.RESIGNATION)

            text == "timeout" -> GameResult(winner, by = ResultReason.TIMEOUT)

            winner == null -> GameResult(null, by = ResultReason.NO_RESULT)

            // Disqualification, abandonment, moderator decisions: won, but not over the board.
            else -> GameResult(winner, by = ResultReason.FORFEIT)
        }
    }
}

/**
 * Game data — the object OGS calls `gamedata` in the REST game and `json` in the overview —
 * read into the shared model. One game data object is everything about a running game: the
 * players, the moves, the clock, the phase.
 */
internal object OgsGameData {
    fun id(gamedata: JsonObject): String =
        gamedata.long("game_id")?.toString() ?: throw GoServerException.Protocol("OGS game data without game_id")

    /** Weekends, vacations, a moderator: any entry in `pause_control` stops the clock. */
    fun isPaused(gamedata: JsonObject): Boolean = gamedata.obj("pause_control")?.isNotEmpty() == true

    /** Correspondence games only; a missing speed is given the benefit of the doubt. */
    fun isCorrespondence(timeControl: JsonObject?): Boolean {
        val speed = timeControl?.str("speed") ?: return true
        return speed == "correspondence"
    }

    /** True for a game Turnzen can draw: square, within the sizes the board supports, two players. */
    fun isDrawable(
        width: Int?,
        height: Int?,
        rengo: Boolean,
    ): Boolean = width != null && width == height && width in BoardSizes.MIN..BoardSizes.MAX && !rengo

    /** The player id of the user's opponent in [gamedata]. */
    fun opponentId(
        gamedata: JsonObject,
        me: Long,
    ): Long? {
        val players = gamedata.obj("players")
        val blackId = gamedata.long("black_player_id") ?: players?.obj("black")?.long("id")
        val whiteId = gamedata.long("white_player_id") ?: players?.obj("white")?.long("id")
        return if (blackId == me) whiteId else blackId
    }

    fun summary(
        gamedata: JsonObject,
        blackPlayer: JsonObject?,
        whitePlayer: JsonObject?,
        me: Long,
        now: Instant,
    ): GameSummary {
        val id = id(gamedata)
        val blackId = gamedata.long("black_player_id") ?: gamedata.obj("players")?.obj("black")?.long("id")
        val myColor = if (blackId == me) StoneColor.BLACK else StoneColor.WHITE
        val players = gamedata.obj("players")
        val opponentShort = players?.obj(if (myColor == StoneColor.BLACK) "white" else "black")
        val opponentFull = if (myColor == StoneColor.BLACK) whitePlayer else blackPlayer
        val width = gamedata.int("width")
        val height = gamedata.int("height")
        val phase = gamedata.str("phase")
        val clock = gamedata.obj("clock")
        val moves = gamedata.arr("moves")?.size ?: 0
        val finished = phase == "finished"
        val action =
            when (phase) {
                "play" -> PendingAction.PLAY_MOVE
                OgsStoneRemoval.PHASE -> PendingAction.SCORE
                else -> PendingAction.OTHER
            }
        // Stone removal is not taken in turns on OGS: both players mark and accept, so it waits
        // on the user until they have accepted the marks as they stand.
        val myTurn =
            when (phase) {
                "play" -> clock?.long("current_player") == me
                OgsStoneRemoval.PHASE -> OgsStoneRemoval.awaits(gamedata, myColor)
                else -> false
            }
        return GameSummary(
            id = GameId(OgsClient.SERVER_ID, id),
            opponent =
                Opponent(
                    handle = opponentFull?.str("username") ?: opponentShort?.str("username") ?: "?",
                    displayName = null,
                    rank = OgsRanks.ofPlayer(opponentFull ?: opponentShort),
                    // Whichever object has it: the full one need not carry the icon.
                    iconUrl = OgsIcons.url(opponentFull) ?: OgsIcons.url(opponentShort),
                ),
            myColor = myColor,
            boardSize = width ?: throw GoServerException.Protocol("OGS game $id without width"),
            boardHeight = height ?: width,
            handicap = gamedata.int("handicap") ?: 0,
            moveNumber = moves,
            lastMoveAt = clock?.long("last_move")?.let(Instant::fromEpochMilliseconds),
            remainingTime =
                if (finished) {
                    null
                } else {
                    OgsClock.remaining(
                        clock,
                        gamedata.obj("time_control"),
                        me,
                        now,
                        isPaused(gamedata),
                    )
                },
            pendingAction = action,
            isMyTurn = myTurn,
            // OGS needs no move context; the move count lets a send notice that the game moved on.
            moveToken = MoveToken(moves.toString()),
            isFinished = finished,
            result =
                if (finished) {
                    OgsOutcome.result(
                        gamedata.str("outcome"),
                        blackLost = gamedata.long("winner")?.let { it != blackId },
                        whiteLost = gamedata.long("winner")?.let { it == blackId },
                    )
                } else {
                    null
                },
            onlyOnWeb = !isDrawable(width, height, gamedata.bool("rengo") == true),
        )
    }

    /** The moves as a replayable record. Handicap stones placed as moves are black's, one after another. */
    fun record(
        gamedata: JsonObject,
        blackName: String?,
        whiteName: String?,
    ): GameRecord {
        val size = gamedata.int("width") ?: throw GoServerException.Protocol("OGS game data without width")
        if (!isDrawable(size, gamedata.int("height"), gamedata.bool("rengo") == true)) {
            throw GoServerException.Protocol("Turnzen cannot draw this board")
        }
        val handicap = gamedata.int("handicap") ?: 0
        val initial = gamedata.obj("initial_state")
        val setupBlack = packedPoints(initial?.str("black"))
        val setupWhite = packedPoints(initial?.str("white"))
        val firstColor = if (gamedata.str("initial_player") == "white") StoneColor.WHITE else StoneColor.BLACK
        // With handicap and no stones set up in advance, black places the stones as moves.
        val placedByMoves = if (handicap >= 2 && setupBlack.isEmpty()) handicap else 0
        val moves =
            (gamedata.arr("moves") ?: JsonArray(emptyList())).mapIndexed { index, element ->
                val move = move(element)
                val color =
                    when {
                        index < placedByMoves -> {
                            StoneColor.BLACK
                        }

                        placedByMoves > 0 -> {
                            if ((index - placedByMoves) % 2 ==
                                0
                            ) {
                                StoneColor.WHITE
                            } else {
                                StoneColor.BLACK
                            }
                        }

                        index % 2 == 0 -> {
                            firstColor
                        }

                        else -> {
                            firstColor.opposite
                        }
                    }
                ColoredMove(color, move)
            }
        return GameRecord(
            boardSize = size,
            komi = gamedata.double("komi"),
            handicap = handicap,
            setupBlack = setupBlack,
            setupWhite = setupWhite,
            moves = moves,
            black = PlayerInfo(blackName),
            white = PlayerInfo(whiteName),
            firstToMove = if (placedByMoves > 0) StoneColor.BLACK else firstColor,
        )
    }

    fun info(
        gamedata: JsonObject,
        blackPlayer: JsonObject?,
        whitePlayer: JsonObject?,
        now: Instant,
    ): GameInfo {
        val players = gamedata.obj("players")
        val clock = gamedata.obj("clock")
        val timeControl = gamedata.obj("time_control")
        val finished = gamedata.str("phase") == "finished"

        fun detail(
            color: String,
            full: JsonObject?,
        ): PlayerDetail {
            val short = players?.obj(color)
            val id = full?.long("id") ?: short?.long("id") ?: gamedata.long("${color}_player_id")
            return PlayerDetail(
                handle = full?.str("username") ?: short?.str("username") ?: "?",
                rank = OgsRanks.ofPlayer(full ?: short),
                country = full?.str("country")?.takeUnless { it == "un" },
                remainingTime =
                    if (finished ||
                        id == null
                    ) {
                        null
                    } else {
                        OgsClock.remaining(clock, timeControl, id, now, isPaused(gamedata))
                    },
            )
        }
        return GameInfo(
            black = detail("black", blackPlayer),
            white = detail("white", whitePlayer),
            komi = gamedata.double("komi"),
            handicap = gamedata.int("handicap") ?: 0,
            ruleset = OgsRules.display(gamedata.str("rules")),
            rated = gamedata.bool("ranked"),
            timeLimit = timeControl?.let(::timeLimit),
            weekendClock = gamedata.bool("pause_on_weekends"),
            startedAt = gamedata.long("start_time")?.let { Instant.fromEpochSeconds(it) },
            scoring = OgsRules.scoring(gamedata.str("rules")),
        )
    }

    /** The time system in the app's words: "Fischer: 3d + 1d, up to 7d". */
    fun timeLimit(tc: JsonObject): String? {
        fun d(key: String) = tc.long(key)?.let { OgsClock.compact(it.seconds) }
        return when (tc.str("system") ?: tc.str("time_control")) {
            "fischer" -> {
                "Fischer: ${d("initial_time")} + ${d("time_increment")}" +
                    (d("max_time")?.let { ", up to $it" } ?: "")
            }

            "byoyomi" -> {
                "Byo-yomi: ${d("main_time")} + ${tc.int("periods")} × ${d("period_time")}"
            }

            "canadian" -> {
                "Canadian: ${d("main_time")} + ${d("period_time")} / ${tc.int("stones_per_period")}"
            }

            "simple" -> {
                "${d("per_move")} per move"
            }

            "absolute" -> {
                "Absolute: ${d("total_time")}"
            }

            "none" -> {
                "No time limit"
            }

            else -> {
                null
            }
        }
    }

    /** One move: `[x, y, ms]`, with `[-1, -1]` for a pass. */
    private fun move(element: kotlinx.serialization.json.JsonElement): Move {
        val array = element as? JsonArray ?: throw GoServerException.Protocol("OGS move is not an array")
        val x = (array.getOrNull(0) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        val y = (array.getOrNull(1) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        if (x == null || y == null) throw GoServerException.Protocol("OGS move without coordinates")
        return if (x < 0 || y < 0) Move.Pass else Move.Play(Point(x, y))
    }

    /** Stones packed as letter pairs ("ddpp"), the way OGS writes an initial state. */
    private fun packedPoints(packed: String?): List<Point> =
        packed
            .orEmpty()
            .chunked(2)
            .filter { it.length == 2 }
            .map { Point(it[0] - 'a', it[1] - 'a') }
}

/**
 * Finished games, from the player's public game list. These entries carry no game data —
 * the list is a summary of summaries — so a row is built from what the list does say.
 */
internal object OgsFinishedGames {
    fun summary(
        entry: JsonObject,
        me: Long,
        ratingChange: Double?,
    ): GameSummary? {
        val id = entry.long("id")?.toString() ?: return null
        val players = entry.obj("players")
        val blackId = entry.long("black") ?: players?.obj("black")?.long("id")
        val myColor = if (blackId == me) StoneColor.BLACK else StoneColor.WHITE
        val opponent = players?.obj(if (myColor == StoneColor.BLACK) "white" else "black")
        val width = entry.int("width") ?: return null
        val height = entry.int("height") ?: width
        return GameSummary(
            id = GameId(OgsClient.SERVER_ID, id),
            opponent =
                Opponent(
                    handle = opponent?.str("username") ?: "?",
                    displayName = null,
                    rank = OgsRanks.ofPlayer(opponent),
                    iconUrl = OgsIcons.url(opponent),
                ),
            myColor = myColor,
            boardSize = width,
            boardHeight = height,
            handicap = entry.int("handicap") ?: 0,
            moveNumber = 0,
            lastMoveAt = entry.str("ended")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            remainingTime = null,
            pendingAction = PendingAction.OTHER,
            isMyTurn = false,
            moveToken = MoveToken(""),
            isFinished = true,
            result =
                OgsOutcome.result(
                    entry.str("outcome"),
                    entry.bool("black_lost"),
                    entry.bool("white_lost"),
                ),
            ratingChange = ratingChange,
            onlyOnWeb = !OgsGameData.isDrawable(width, height, entry.bool("rengo") == true),
        )
    }

    fun isCorrespondence(entry: JsonObject): Boolean {
        val params = OgsJson.objectOrEncoded(entry["time_control_parameters"])
        return OgsGameData.isCorrespondence(params)
    }
}

/**
 * The player's rating history, which OGS serves as tab-separated text, newest game first:
 * a row per rated game with the rating *after* it. What one game did is the difference to
 * the row before it (the next one down). The first row of all is the starting rating
 * (game id 0), which only ever serves as a "before".
 */
internal object OgsRatingHistory {
    fun changes(tsv: String): Map<String, Double> {
        val lines = tsv.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyMap()
        val header = lines.first().split('\t')
        val endedAt = header.indexOf("ended")
        val gameAt = header.indexOf("game_id")
        val ratingAt = header.indexOf("rating")
        if (gameAt < 0 || ratingAt < 0) return emptyMap()
        val rows =
            lines
                .drop(1)
                .mapNotNull { line ->
                    val cells = line.split('\t')
                    val game = cells.getOrNull(gameAt) ?: return@mapNotNull null
                    val rating = cells.getOrNull(ratingAt)?.toDoubleOrNull() ?: return@mapNotNull null
                    Triple(cells.getOrNull(endedAt)?.toLongOrNull() ?: 0L, game, rating)
                }.sortedBy { it.first }
        return rows
            .zipWithNext()
            .filter { (_, after) -> after.second != "0" }
            .associate { (before, after) ->
                after.second to OgsRanks.ranking(after.third) - OgsRanks.ranking(before.third)
            }
    }
}

internal object OgsUser {
    /** The signed-in user, from `/api/v1/ui/config`'s `user` block. */
    fun parse(user: JsonObject?): SignedInUser {
        user ?: throw GoServerException.Protocol("OGS sent no user")
        val id = user.long("id") ?: throw GoServerException.Protocol("OGS user without id")
        if (id <= 0) throw GoServerException.SessionExpired("Online Go Server does not know who this is")
        return SignedInUser(
            id = id.toString(),
            handle = user.str("username") ?: throw GoServerException.Protocol("OGS user without username"),
            displayName = null,
            rank = OgsRanks.ofPlayer(user),
        )
    }
}
