package dev.turnzen.dgs

import dev.turnzen.goserver.GameId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the recorded DGS Ladder page. The account it came from is Hungarian, so every one
 * of these passing is also the proof that nothing keys on an English word.
 */
class DgsLadderPageTest {
    private val page = Fixtures.text("ladder_view_tid3.html")

    @Test
    fun `reads every challenge the user is part of, from both sides`() {
        val standings = DgsLadderPage.standings(page)

        assertEquals(4, standings.size)
        // The one the user started: they are ranked 153 and called out 108 above them.
        val outgoing = standings.getValue(GameId(DgsClient.SERVER_ID, "1503981"))
        assertEquals(153, outgoing.yourRank)
        assertEquals(108, outgoing.opponentRank)
        assertTrue(outgoing.youAreChallenger)

        // And three being defended, read out of the user's own Challenges-In cell.
        assertEquals(
            setOf(149, 209, 116),
            standings
                .filterKeys {
                    it.value != "1503981"
                }.values
                .map { it.opponentRank }
                .toSet(),
        )
        assertTrue(standings.filterKeys { it.value != "1503981" }.values.none { it.youAreChallenger })
    }

    @Test
    fun `the challenger is not the worse-placed player`() {
        // Game 1501653: the challenger sits at 149, *above* the defender at 153, having climbed
        // past them while the game ran. Reading direction off the two ranks would invert this.
        val defended = DgsLadderPage.standings(page).getValue(GameId(DgsClient.SERVER_ID, "1501653"))
        assertEquals(153, defended.yourRank)
        assertEquals(149, defended.opponentRank)
        assertTrue(!defended.youAreChallenger)
    }

    @Test
    fun `other players' challenges are not mistaken for the user's own`() {
        // The page lists every running challenge on the ladder, most of them between strangers.
        val standings = DgsLadderPage.standings(page)
        assertTrue(standings.values.all { it.yourRank == 153 })
    }

    @Test
    fun `hiding the optional column changes the cell positions but not the answer`() {
        // Challenges-Out is the one of the two that a user can hide, which moves every cell
        // after it. Reading by the header's column ids has to absorb that.
        val narrowed = withoutChallengesOut(page)
        assertTrue(!narrowed.contains("<th id=\"Col16\""))

        val standings = DgsLadderPage.standings(narrowed)
        assertEquals(DgsLadderPage.standings(page), standings)
    }

    @Test
    fun `a challenge survives hiding the column it was started from`() {
        // Worth stating on its own: Challenges-In is never hideable, and every challenge
        // appears in the defender's row. So the game the user started is still found -- from
        // the other player's row -- and still known to be theirs.
        val outgoing =
            DgsLadderPage
                .standings(
                    withoutChallengesOut(page),
                ).getValue(GameId(DgsClient.SERVER_ID, "1503981"))
        assertEquals(153, outgoing.yourRank)
        assertEquals(108, outgoing.opponentRank)
        assertTrue(outgoing.youAreChallenger)
    }

    @Test
    fun `a page with no table, no user row or no header yields nothing`() {
        assertEquals(emptyMap(), DgsLadderPage.standings("<html><body>Sorry.</body></html>"))
        assertEquals(emptyMap(), DgsLadderPage.standings(""))
        // A ladder the user does not take part in: rows, but no row of their own.
        assertEquals(emptyMap(), DgsLadderPage.standings(page.replace("TourneyUser", "SomeoneElse")))
    }

    /** Removes column 16 from the header and the cell at its position from every row. */
    private fun withoutChallengesOut(html: String): String {
        val dotAll = setOf(RegexOption.DOT_MATCHES_ALL)
        val headerRow = Regex("""<tr[^>]*\bid="TableHead"[^>]*>.*?</tr>""", dotAll).find(html)!!.value
        val columns = Regex("""<th[^>]*\bid="Col(\d+)"""").findAll(headerRow).map { it.groupValues[1] }.toList()
        val position = columns.indexOf("16")
        val cell = Regex("""<td\b[^>]*>.*?</td>""", dotAll)

        var out = html.replace(headerRow, Regex("""<th id="Col16".*?</th>""", dotAll).replace(headerRow, ""))
        for (row in Regex("""<tr class="Row\d+[^"]*"[^>]*>.*?</tr>""", dotAll).findAll(html)) {
            val cells = cell.findAll(row.value).toList()
            val drop = cells.getOrNull(position) ?: continue
            out = out.replace(row.value, row.value.replaceRange(drop.range, ""))
        }
        return out
    }
}
