package dev.turnzen.dgs

import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.LadderStanding

/**
 * Reads ladder standings out of DGS's `tournaments/ladder/view.php` page.
 *
 * This is the one place in the client that parses a page meant for humans, because the ranks
 * exist nowhere else: there is no tournament object in the QuickSuite at all. See
 * `docs/dgs-ladder-standings.md` for how that was established and for the page's shape.
 *
 * The parsing keys on structure and numbers only — never on a word — because the page is
 * served in the account's language. Three markers carry everything:
 *
 * - `<tr id="TableHead">` lists one `<th id="Col<N>">` per *displayed* column, in the order
 *   the cells of every row will appear. Which columns are displayed is a per-user preference,
 *   so this header is the calibration that makes reading cells by position safe.
 * - each row's `<a name="rank<N>">` is that player's rank, and `TourneyUser` in the row's
 *   class marks the logged-in user's own row.
 * - a running challenge renders as `[<a href="…game.php?gid=<g>">#<R></a>]`, in column 8
 *   (Challenges-In) of the defender's row and column 16 (Challenges-Out) of the challenger's.
 *   Which column it sits in is the only reliable statement of who challenged whom: ranks move
 *   while a game runs, so the challenger is not dependably the worse-placed of the two.
 *
 * Anything unreadable yields no standing rather than an error. Nothing here is needed to play.
 */
internal object DgsLadderPage {
    /** Challenges *against* the row's player. Never hideable, so always present. */
    private const val COLUMN_CHALLENGES_IN = 8

    /** Challenges *by* the row's player. Hideable, so it may be missing. */
    private const val COLUMN_CHALLENGES_OUT = 16

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val HEADER_ROW = Regex("""<tr[^>]*\bid="TableHead"[^>]*>(.*?)</tr>""", OPTIONS)
    private val HEADER_COLUMN = Regex("""<th[^>]*\bid="Col(\d+)"""", RegexOption.IGNORE_CASE)
    private val ROW = Regex("""<tr\b([^>]*)>(.*?)</tr>""", OPTIONS)
    private val RANK = Regex("""\bname="rank(\d+)"""", RegexOption.IGNORE_CASE)
    private val CELL = Regex("""<td\b[^>]*>(.*?)</td>""", OPTIONS)

    /** `<a href="../../game.php?gid=1501653">#149</a>` — the game, and the other player's rank. */
    private val CHALLENGE = Regex("""\bgame\.php\?gid=(\d+)"[^>]*>\s*#(\d+)\s*<""", RegexOption.IGNORE_CASE)

    /**
     * Every running challenge on this page that the logged-in user is part of, keyed by game.
     * Empty when the page carries no table, no user row, or no challenges — all of which are
     * ordinary situations rather than failures.
     */
    fun standings(html: String): Map<GameId, LadderStanding> {
        val columns = columns(html) ?: return emptyMap()
        val challengesIn = columns.indexOf(COLUMN_CHALLENGES_IN)
        if (challengesIn < 0) return emptyMap()
        val challengesOut = columns.indexOf(COLUMN_CHALLENGES_OUT)

        var myRank = 0
        // Game id to the pair of ranks, defender first. One row is enough to fill an entry;
        // when both columns are shown the two sides agree, so the second sighting is ignored.
        val challenges = LinkedHashMap<String, Pair<Int, Int>>()

        for (row in ROW.findAll(html)) {
            val (attributes, body) = row.destructured
            val rank =
                RANK
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: continue
            if (attributes.contains("TourneyUser")) myRank = rank
            val cells = CELL.findAll(body).map { it.groupValues[1] }.toList()
            cells.getOrNull(challengesIn)?.let { cell ->
                for (m in CHALLENGE.findAll(cell)) {
                    challenges.getOrPut(m.groupValues[1]) { rank to m.groupValues[2].toInt() }
                }
            }
            if (challengesOut >= 0) {
                cells.getOrNull(challengesOut)?.let { cell ->
                    for (m in CHALLENGE.findAll(cell)) {
                        challenges.getOrPut(m.groupValues[1]) { m.groupValues[2].toInt() to rank }
                    }
                }
            }
        }
        if (myRank == 0) return emptyMap() // the user does not take part in this ladder

        return buildMap {
            for ((gid, ranks) in challenges) {
                val (defender, challenger) = ranks
                val standing =
                    when (myRank) {
                        challenger -> LadderStanding(myRank, defender, youAreChallenger = true)
                        defender -> LadderStanding(myRank, challenger, youAreChallenger = false)
                        else -> null // somebody else's challenge; the page lists every one of them
                    }
                if (standing != null) put(GameId(gid), standing)
            }
        }
    }

    /** The displayed column numbers, in cell order, or null when this is not the ladder page. */
    private fun columns(html: String): List<Int>? {
        val header = HEADER_ROW.find(html)?.groupValues?.get(1) ?: return null
        val columns = HEADER_COLUMN.findAll(header).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return columns.ifEmpty { null }
    }
}
