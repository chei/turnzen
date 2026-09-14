package dev.turnzen.game

/**
 * How a ruleset turns a finished board into points.
 *
 * [TERRITORY] (Japanese and its relatives) counts surrounded empty points plus captured
 * stones; [AREA] (Chinese and its relatives) counts surrounded empty points plus the stones
 * still standing, and ignores captures. The difference is usually a point or two, and a
 * player who cares will notice, so the count is never shown without knowing which applies.
 */
enum class ScoringRules { TERRITORY, AREA }

/** Why a game ended, and — when it was counted — by how much. */
enum class ResultReason { POINTS, RESIGNATION, TIMEOUT, FORFEIT, JIGO, NO_RESULT }

/**
 * The outcome of a game. [winner] is null for a draw or a void game, and [margin] is set
 * only when the game was decided on points.
 */
data class GameResult(
    val winner: StoneColor?,
    val margin: Double? = null,
    val by: ResultReason = ResultReason.POINTS,
) {
    companion object {
        /** The result of a count: black's points against white's. */
        fun fromPoints(
            black: Double,
            white: Double,
        ): GameResult {
            val margin = black - white
            return when {
                margin > 0 -> GameResult(StoneColor.BLACK, margin, ResultReason.POINTS)
                margin < 0 -> GameResult(StoneColor.WHITE, -margin, ResultReason.POINTS)
                else -> GameResult(null, 0.0, ResultReason.JIGO)
            }
        }
    }
}

/**
 * What the stones on a scored board are worth: the empty points each side surrounds, the
 * points neither side does, and the totals those add up to under the game's rules.
 */
data class ScoreCount(
    val territory: Map<StoneColor, Set<Point>>,
    /** Empty points bordering both colours: nobody's, and not worth a point to anyone. */
    val dame: Set<Point>,
    /** Stones each side has taken: captures during play plus the opponent's dead stones. */
    val captures: Map<StoneColor, Int>,
    val blackPoints: Double,
    val whitePoints: Double,
) {
    fun territoryOf(color: StoneColor): Set<Point> = territory[color].orEmpty()

    fun capturesOf(color: StoneColor): Int = captures[color] ?: 0

    val result: GameResult
        get() = GameResult.fromPoints(blackPoints, whitePoints)
}

/**
 * Counting a finished board, once both players agree which stones are dead.
 *
 * This is the *local* count, shown live while the user marks stones so they can see what
 * their marks are worth. The server does its own counting and its answer is the one that
 * settles the game; the two agree on any position whose dead stones are agreed, which is
 * exactly the position a player is looking at when they accept a score.
 */
object Scoring {
    /**
     * Counts [position] with [dead] treated as stones that have been captured.
     *
     * Dead stones are lifted off the board first, so the points they stood on count as their
     * captor's territory — which is how both rulesets treat them, and why the marking step
     * exists at all.
     */
    fun count(
        position: Position,
        dead: Set<Point> = emptySet(),
        komi: Double = 0.0,
        rules: ScoringRules = ScoringRules.TERRITORY,
    ): ScoreCount {
        val board = position.board
        val alive = board.without(dead)
        val territory = HashMap<StoneColor, MutableSet<Point>>()
        val dame = LinkedHashSet<Point>()

        for (region in emptyRegions(alive)) {
            val owner = region.borderedBy.singleOrNull()
            if (owner == null) {
                dame += region.points
            } else {
                territory.getOrPut(owner) { LinkedHashSet() } += region.points
            }
        }

        // A dead stone is a prisoner of the other side, on top of what was taken in play.
        val deadByColor = dead.groupingBy { board[it] }.eachCount()
        val captures =
            StoneColor.entries.associateWith { color ->
                position.prisonersOf(color) + (deadByColor[color.opposite] ?: 0)
            }

        val blackArea = territory[StoneColor.BLACK]?.size ?: 0
        val whiteArea = territory[StoneColor.WHITE]?.size ?: 0
        val black: Double
        val white: Double
        when (rules) {
            ScoringRules.TERRITORY -> {
                black = (blackArea + (captures[StoneColor.BLACK] ?: 0)).toDouble()
                white = (whiteArea + (captures[StoneColor.WHITE] ?: 0)).toDouble() + komi
            }

            ScoringRules.AREA -> {
                // Stones still standing count themselves; captures do not enter the sum.
                black = (blackArea + alive.count(StoneColor.BLACK)).toDouble()
                white = (whiteArea + alive.count(StoneColor.WHITE)).toDouble() + komi
            }
        }
        return ScoreCount(
            territory = territory.mapValues { (_, points) -> points.toSet() },
            dame = dame,
            captures = captures,
            blackPoints = black,
            whitePoints = white,
        )
    }

    /**
     * The stones that die together with the one at [point] — its connected group, which is
     * what a tap on a dead stone means. Empty when the point holds no stone.
     */
    fun groupAt(
        board: Board,
        point: Point,
    ): Set<Point> = board.group(point)

    private class Region(
        val points: Set<Point>,
        val borderedBy: Set<StoneColor>,
    )

    /** Every maximal run of connected empty points, with the colours that enclose it. */
    private fun emptyRegions(board: Board): List<Region> {
        val seen = HashSet<Point>()
        val regions = ArrayList<Region>()
        for (row in 0 until board.size) {
            for (col in 0 until board.size) {
                val start = Point(col, row)
                if (start in seen || board[start] != null) continue
                val points = LinkedHashSet<Point>()
                val borders = LinkedHashSet<StoneColor>()
                val stack = ArrayDeque<Point>()
                stack += start
                seen += start
                points += start
                while (stack.isNotEmpty()) {
                    val current = stack.removeLast()
                    for (n in board.neighbors(current)) {
                        val stone = board[n]
                        if (stone != null) {
                            borders += stone
                        } else if (n !in seen) {
                            seen += n
                            points += n
                            stack += n
                        }
                    }
                }
                regions += Region(points, borders)
            }
        }
        return regions
    }
}
