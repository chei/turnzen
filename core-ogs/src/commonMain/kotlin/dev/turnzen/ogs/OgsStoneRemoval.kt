package dev.turnzen.ogs

import dev.turnzen.game.DeadStones
import dev.turnzen.game.Point
import dev.turnzen.game.Position
import dev.turnzen.game.Replay
import dev.turnzen.game.Scoring
import dev.turnzen.game.ScoringRules
import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfCoordinates
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ScoreState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * OGS's stone removal phase, read from game data. See the brief, "Scoring on OGS".
 *
 * The phase holds one set of marks for the game, `removed`: the dead stones, plus any empty
 * points a player has marked as dame. Either player may change it at any time, and the game
 * ends when both have accepted the same marks — each player's acceptance is kept as
 * `players.<colour>.accepted_stones`, and is out of date as soon as the marks change.
 */
internal object OgsStoneRemoval {
    const val PHASE = "stone removal"

    /** The marks as the server holds them, on the position they apply to. */
    class Marks(
        val position: Position,
        /** Everything marked, dame points included. */
        val marked: Set<Point>,
        val komi: Double,
        val rules: ScoringRules,
        val handicap: Int,
    ) {
        /** The marked points that hold a stone. */
        val dead: Set<Point> = marked.filterTo(LinkedHashSet()) { position.board[it] != null }

        /** The marks in the server's own form, which is also how two sets of marks are compared. */
        val encoded: String = encode(marked)
    }

    /** The marks in [gamedata]; a game that is not being counted has moved on from the caller. */
    fun read(gamedata: JsonObject): Marks {
        if (gamedata.str("phase") != PHASE) throw GoServerException.StaleGame()
        val record = OgsGameData.record(gamedata, null, null)
        return Marks(
            position = Replay(record).finalPosition,
            marked = points(gamedata["removed"], record.boardSize),
            komi = gamedata.double("komi") ?: 0.0,
            rules = OgsRules.scoring(gamedata.str("rules")) ?: ScoringRules.TERRITORY,
            handicap = gamedata.int("handicap") ?: 0,
        )
    }

    /**
     * The phase as the app shows it, after the user's [toggle] taps on the server's marks.
     *
     * OGS counts nothing until the game is over, so the count is this device's (`Scoring.count`)
     * — the same arithmetic the app shows while the user marks, so the two never disagree.
     */
    fun state(
        marks: Marks,
        toggle: List<Point> = emptyList(),
    ): ScoreState {
        val dead = toggle.fold(marks.dead) { d, tap -> DeadStones.toggle(marks.position.board, d, tap) }
        val count = Scoring.count(marks.position, dead, marks.komi, marks.rules, marks.handicap)
        return ScoreState(
            result = count.result,
            rules = marks.rules,
            deadStones = dead,
            blackTerritory = count.territoryOf(StoneColor.BLACK),
            whiteTerritory = count.territoryOf(StoneColor.WHITE),
            dame = count.dame,
        )
    }

    /**
     * True while the phase waits on the player of [color]: they have not accepted the marks as
     * they now stand. Both players are asked at once, so this is not a turn in the usual sense,
     * but it is what the games list needs to know.
     */
    fun awaits(
        gamedata: JsonObject,
        color: StoneColor,
    ): Boolean {
        val size = gamedata.int("width") ?: return true
        val accepted =
            gamedata
                .obj("players")
                ?.obj(if (color == StoneColor.BLACK) "black" else "white")
                ?.get("accepted_stones") as? JsonPrimitive
        if (accepted == null || !accepted.isString) return true
        return encode(points(accepted, size)) != encode(points(gamedata["removed"], size))
    }

    /**
     * Points as OGS writes them: a string of two-letter coordinates ("aabb", "aa" the top-left
     * corner) or, in newer data, a list of `{x, y}` objects. Anything off the board is dropped.
     */
    fun points(
        element: JsonElement?,
        size: Int,
    ): Set<Point> {
        val all =
            when (element) {
                is JsonPrimitive -> {
                    if (!element.isString) {
                        emptyList()
                    } else {
                        element.content
                            .chunked(2)
                            .filter { it.length == 2 }
                            .map { Point(it[0] - 'a', it[1] - 'a') }
                    }
                }

                is JsonArray -> {
                    element.mapNotNull { entry ->
                        val o = entry as? JsonObject ?: return@mapNotNull null
                        val x = o.int("x") ?: return@mapNotNull null
                        val y = o.int("y") ?: return@mapNotNull null
                        Point(x, y)
                    }
                }

                else -> {
                    emptyList()
                }
            }
        return all.filterTo(LinkedHashSet()) { it.col in 0 until size && it.row in 0 until size }
    }

    /** Row by row from the top, the order OGS itself writes marks in when a player accepts them. */
    fun encode(points: Collection<Point>): String =
        points
            .sortedWith(compareBy<Point> { it.row }.thenBy { it.col })
            .joinToString("") { SgfCoordinates.encode(it) }
}
