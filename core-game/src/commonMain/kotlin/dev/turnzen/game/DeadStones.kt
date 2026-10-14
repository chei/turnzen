package dev.turnzen.game

import kotlin.random.Random

/**
 * Marking dead stones at the end of a game: what one tap flips, and a first guess at the
 * whole answer so the user only has to correct it.
 *
 * A tap flips an *area*, not a single chain: every stone of the tapped colour and state
 * (alive or dead) that can be reached from it through empty points and stones like it. That
 * is how a corner full of dead stones goes in one tap — and it is exactly what DGS does with
 * a coordinate in its scoring commands, so the board the user sees and the board the server
 * computes from the same taps never part company. Killing an area also brings back to life
 * any dead area of the other colour touching it: if these stones are dead, whatever
 * surrounded them was not.
 */
object DeadStones {
    /**
     * The dead stones after a tap on [at], starting from [dead]. Tapping an empty point
     * changes nothing.
     */
    fun toggle(
        board: Board,
        dead: Set<Point>,
        at: Point,
    ): Set<Point> {
        val color = board[at] ?: return dead
        val killing = at !in dead
        val (stones, reached) = area(board, dead, at)
        val result = LinkedHashSet(dead)
        if (!killing) {
            result -= stones
            return result
        }
        result += stones
        for (point in reached) {
            for (n in board.neighbors(point)) {
                if (board[n] == color.opposite && n in result) {
                    result -= area(board, dead, n).first
                }
            }
        }
        return result
    }

    /**
     * The stones of [at]'s colour and state reachable through empty points and each other,
     * and every point the search reached on the way (the stones and the empty points).
     */
    private fun area(
        board: Board,
        dead: Set<Point>,
        at: Point,
    ): Pair<Set<Point>, Set<Point>> {
        val color = board[at]
        val isDead = at in dead
        val reached = LinkedHashSet<Point>()
        val stack = ArrayDeque<Point>()
        reached += at
        stack += at
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (n in board.neighbors(current)) {
                if (n in reached) continue
                val stone = board[n]
                if (stone == null || (stone == color && (n in dead) == isDead)) {
                    reached += n
                    stack += n
                }
            }
        }
        return reached.filterTo(LinkedHashSet()) { board[it] == color } to reached
    }

    /**
     * The taps that mark this device's guess at the dead stones, from a board with none
     * marked. Applied in order with [toggle] they give the guess; returned as taps rather than
     * as stones because taps are what a server is told.
     *
     * An area is tapped only when every stone it would flip is one the guess holds dead, so
     * the guess can leave a stone alive but never kills one it was not sure of.
     */
    fun guessTaps(
        position: Position,
        playouts: Int = DEFAULT_PLAYOUTS,
        random: Random = Random(seedOf(position.board)),
    ): List<Point> {
        val board = position.board
        val guess = guess(position, playouts, random)
        var dead: Set<Point> = emptySet()
        val taps = ArrayList<Point>()
        for (row in 0 until board.size) {
            for (col in 0 until board.size) {
                val point = Point(col, row)
                if (point !in guess || point in dead) continue
                val next = toggle(board, dead, point)
                if (next.containsAll(dead) && guess.containsAll(next)) {
                    dead = next
                    taps += point
                }
            }
        }
        return taps
    }

    /**
     * The stones this device believes are dead.
     *
     * First the position is played out at random many times, to see which stones are usually
     * gone by the end. Random play is a poor player but a fair judge of a finished game: a
     * group with two eyes survives because nobody may fill them, a group without them is
     * captured sooner or later, and stones with little room inside the opponent's territory
     * are taken nearly every time. A chain counts as dead when the opponent ends up owning its
     * points in a clear majority of the playouts, and as alive when its owner keeps them.
     *
     * What random play cannot judge is a few stones with a lot of room: in a wide territory
     * both sides fill the space about equally and the stones come out even. Those undecided
     * chains get a second look ([walledIn]): dead when everything around them is a wall of
     * the opponent's living stones, nothing of their own colour that lives can be reached, and
     * they have not made two eyes or a territory. A seki stays alive on both counts — random play kills each
     * side about half the time, and each group's surroundings include the other, undecided one.
     */
    fun guess(
        position: Position,
        playouts: Int = DEFAULT_PLAYOUTS,
        random: Random = Random(seedOf(position.board)),
    ): Set<Point> {
        val board = position.board
        val playout = Playout(board)
        val ownership = IntArray(board.size * board.size)
        repeat(playouts) { i ->
            playout.reset()
            playout.run(if (i % 2 == 0) BLACK else WHITE, random)
            playout.addOwnership(ownership)
        }
        val status = HashMap<Point, Status>()
        val chains = ArrayList<Set<Point>>()
        for ((point, color) in board.stones()) {
            if (point in status) continue
            val chain = board.group(point)
            chains += chain
            // Positive ownership is black's; turn it into "how much does this chain's owner keep".
            val sign = if (color == StoneColor.BLACK) 1 else -1
            val kept =
                chain.sumOf { sign * ownership[it.row * board.size + it.col] }.toDouble() / (chain.size * playouts)
            val verdict =
                when {
                    kept < -DEAD_THRESHOLD -> Status.DEAD
                    kept > ALIVE_THRESHOLD -> Status.ALIVE
                    else -> Status.UNSURE
                }
            for (p in chain) status[p] = verdict
        }
        val dead = LinkedHashSet<Point>()
        for (chain in chains) {
            when (status.getValue(chain.first())) {
                Status.DEAD -> dead += chain
                Status.UNSURE -> if (walledIn(board, status, chain)) dead += chain
                Status.ALIVE -> Unit
            }
        }
        return dead
    }

    /**
     * A seed that depends only on the stones, so the same position always gets the same guess.
     * (The board's own hash code goes through enum identity hashes, which change per process.)
     */
    private fun seedOf(board: Board): Int =
        board.stones().fold(board.size) { acc, (p, color) ->
            31 * acc + (p.row * board.size + p.col) * 2 + color.ordinal
        }

    private enum class Status { ALIVE, DEAD, UNSURE }

    /**
     * True when [chain] sits in a region enclosed entirely by the opponent's living stones:
     * the region reached from it through empty points, its own colour's undecided or dead
     * stones, and the opponent's dead ones. Reaching a living stone of its own colour means it
     * can connect out; reaching an undecided opponent stone means the two are fighting it out
     * (a seki, or a capturing race nobody finished), and neither is called. Two eyes, or room
     * enough to count as two ([eyes]), keep it alive too.
     */
    private fun walledIn(
        board: Board,
        status: Map<Point, Status>,
        chain: Set<Point>,
    ): Boolean {
        val color = board[chain.first()] ?: return false
        val region = LinkedHashSet<Point>(chain)
        val stack = ArrayDeque(chain)
        var walls = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (n in board.neighbors(current)) {
                if (n in region) continue
                val stone = board[n]
                val passes =
                    when {
                        stone == null -> true
                        stone == color -> if (status[n] == Status.ALIVE) return false else true
                        status[n] == Status.ALIVE -> false
                        status[n] == Status.DEAD -> true
                        else -> return false
                    }
                if (passes) {
                    region += n
                    stack += n
                } else {
                    walls++
                }
            }
        }
        if (walls == 0) return false
        return eyes(board, status, region, color) < 2
    }

    /**
     * The eyes [color] has in [region]: separate spaces bordered by its stones alone, where a
     * dead opponent stone counts as space. A space of [BIG_EYE] points or more counts twice —
     * that much room is a territory, not something the opponent can still take away.
     */
    private fun eyes(
        board: Board,
        status: Map<Point, Status>,
        region: Set<Point>,
        color: StoneColor,
    ): Int {
        fun isSpace(p: Point): Boolean {
            val stone = board[p]
            return stone == null || (stone != color && status[p] == Status.DEAD)
        }
        val seen = HashSet<Point>()
        var eyes = 0
        for (start in region) {
            if (!isSpace(start) || start in seen) continue
            var own = true
            var size = 0
            val stack = ArrayDeque<Point>()
            stack += start
            seen += start
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                size++
                for (n in board.neighbors(current)) {
                    if (isSpace(n)) {
                        if (n !in seen) {
                            seen += n
                            stack += n
                        }
                    } else if (board[n] != color) {
                        own = false
                    }
                }
            }
            if (own) eyes += if (size >= BIG_EYE) 2 else 1
        }
        return eyes
    }

    private const val DEFAULT_PLAYOUTS = 200

    /** How firmly the opponent must own a chain's points, on a scale of -1..1, to call it dead. */
    private const val DEAD_THRESHOLD = 0.4

    /** Room enough to live in, for [eyes]. */
    private const val BIG_EYE = 7

    /** How firmly a chain's owner must keep its points to count as living, for [walledIn]. */
    private const val ALIVE_THRESHOLD = 0.5

    private const val EMPTY = 0
    private const val BLACK = 1
    private const val WHITE = 2

    /** A mutable board for fast random play; nothing here leaves this file. */
    private class Playout(
        start: Board,
    ) {
        private val size = start.size
        private val initial = IntArray(size * size)
        private val cells = IntArray(size * size)
        private val neighbors: Array<IntArray>
        private val mark = IntArray(size * size)
        private var markGen = 0
        private val stack = IntArray(size * size)
        private val group = IntArray(size * size)
        private val candidates = IntArray(size * size)
        private var ko = -1

        init {
            for ((point, color) in start.stones()) {
                initial[point.row * size + point.col] = if (color == StoneColor.BLACK) BLACK else WHITE
            }
            neighbors =
                Array(size * size) { i ->
                    start.neighbors(Point(i % size, i / size)).map { it.row * size + it.col }.toIntArray()
                }
        }

        fun reset() {
            initial.copyInto(cells)
            ko = -1
        }

        fun run(
            first: Int,
            random: Random,
        ) {
            var toMove = first
            var passes = 0
            var moves = 0
            val limit = size * size * 3
            while (passes < 2 && moves < limit) {
                if (playRandom(toMove, random)) passes = 0 else passes++
                toMove = 3 - toMove
                moves++
            }
        }

        /** Positive for black's points, negative for white's, from where the playout ended. */
        fun addOwnership(ownership: IntArray) {
            for (i in cells.indices) {
                val owner = if (cells[i] == EMPTY) enclosedBy(i) else cells[i]
                ownership[i] +=
                    if (owner == BLACK) {
                        1
                    } else if (owner == WHITE) {
                        -1
                    } else {
                        0
                    }
            }
        }

        /** The colour of every stone next to an empty point, or [EMPTY] when they differ. */
        private fun enclosedBy(point: Int): Int {
            var owner = EMPTY
            for (n in neighbors[point]) {
                val c = cells[n]
                if (c == EMPTY || c == owner) continue
                if (owner != EMPTY) return EMPTY
                owner = c
            }
            return owner
        }

        /** Plays a random legal move that does not fill the mover's own eye; false for a pass. */
        private fun playRandom(
            color: Int,
            random: Random,
        ): Boolean {
            var count = 0
            for (i in cells.indices) {
                if (cells[i] == EMPTY && i != ko && !isOwnEye(i, color)) candidates[count++] = i
            }
            while (count > 0) {
                val pick = random.nextInt(count)
                val point = candidates[pick]
                if (tryPlay(point, color)) return true
                candidates[pick] = candidates[--count]
            }
            ko = -1
            return false
        }

        private fun isOwnEye(
            point: Int,
            color: Int,
        ): Boolean = neighbors[point].all { cells[it] == color }

        private fun tryPlay(
            point: Int,
            color: Int,
        ): Boolean {
            val enemy = 3 - color
            cells[point] = color
            var captured = 0
            var capturedAt = -1
            for (n in neighbors[point]) {
                if (cells[n] == enemy) {
                    val length = chainWithoutLiberty(n)
                    if (length > 0) {
                        for (k in 0 until length) cells[group[k]] = EMPTY
                        captured += length
                        capturedAt = n
                    }
                }
            }
            // A stone with an empty point beside it has a liberty, and its chain with it:
            // the chain only needs walking when the stone filled its last one.
            if (libertyCount(point) == 0 && chainWithoutLiberty(point) > 0) {
                // Suicide; nothing was captured or the group would have a liberty.
                cells[point] = EMPTY
                return false
            }
            // A lone stone that took a lone stone and sits in its place with one liberty: ko.
            ko = if (captured == 1 && isLone(point) && libertyCount(point) == 1) capturedAt else -1
            return true
        }

        /**
         * The length of the chain at [start] when it has no liberty, with its points in
         * [group]; 0 as soon as a liberty turns up. Most chains have one a step or two away,
         * so stopping there spares walking a long chain on every move.
         */
        private fun chainWithoutLiberty(start: Int): Int {
            val color = cells[start]
            markGen++
            var top = 0
            var length = 0
            stack[top++] = start
            mark[start] = markGen
            while (top > 0) {
                val current = stack[--top]
                group[length++] = current
                for (n in neighbors[current]) {
                    val c = cells[n]
                    if (c == EMPTY) return 0
                    if (c == color && mark[n] != markGen) {
                        mark[n] = markGen
                        stack[top++] = n
                    }
                }
            }
            return length
        }

        /** True when no stone of the same colour touches [point]. */
        private fun isLone(point: Int): Boolean {
            val color = cells[point]
            for (n in neighbors[point]) if (cells[n] == color) return false
            return true
        }

        private fun libertyCount(point: Int): Int = neighbors[point].count { cells[it] == EMPTY }
    }
}
