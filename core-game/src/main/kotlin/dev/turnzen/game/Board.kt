package dev.turnzen.game

/** Immutable stone layout. All mutators return a new board. */
class Board private constructor(
    val size: Int,
    private val cells: Array<StoneColor?>,
) {
    companion object {
        fun empty(size: Int): Board {
            BoardSizes.requireValid(size)
            return Board(size, arrayOfNulls(size * size))
        }
    }

    private fun index(point: Point) = point.row * size + point.col

    operator fun contains(point: Point): Boolean = point.col < size && point.row < size

    operator fun get(point: Point): StoneColor? {
        require(point in this) { "$point is outside a ${size}x$size board" }
        return cells[index(point)]
    }

    fun with(
        point: Point,
        color: StoneColor?,
    ): Board {
        require(point in this) { "$point is outside a ${size}x$size board" }
        val copy = cells.copyOf()
        copy[index(point)] = color
        return Board(size, copy)
    }

    fun without(points: Iterable<Point>): Board {
        val copy = cells.copyOf()
        for (p in points) copy[index(p)] = null
        return Board(size, copy)
    }

    fun neighbors(point: Point): List<Point> {
        val result = ArrayList<Point>(4)
        if (point.col > 0) result += Point(point.col - 1, point.row)
        if (point.col < size - 1) result += Point(point.col + 1, point.row)
        if (point.row > 0) result += Point(point.col, point.row - 1)
        if (point.row < size - 1) result += Point(point.col, point.row + 1)
        return result
    }

    /** The connected group of stones containing [point]; empty when the point is empty. */
    fun group(point: Point): Set<Point> {
        val color = this[point] ?: return emptySet()
        val seen = LinkedHashSet<Point>()
        val stack = ArrayDeque<Point>()
        stack += point
        seen += point
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (n in neighbors(current)) {
                if (n !in seen && this[n] == color) {
                    seen += n
                    stack += n
                }
            }
        }
        return seen
    }

    fun liberties(group: Set<Point>): Set<Point> {
        val result = LinkedHashSet<Point>()
        for (p in group) {
            for (n in neighbors(p)) if (this[n] == null) result += n
        }
        return result
    }

    fun stones(): List<Pair<Point, StoneColor>> {
        val result = ArrayList<Pair<Point, StoneColor>>()
        for (i in cells.indices) {
            val c = cells[i] ?: continue
            result += Point(i % size, i / size) to c
        }
        return result
    }

    fun count(color: StoneColor): Int = cells.count { it == color }

    override fun equals(other: Any?): Boolean = other is Board && other.size == size && other.cells.contentEquals(cells)

    override fun hashCode(): Int = 31 * size + cells.contentHashCode()

    override fun toString(): String =
        buildString {
            for (row in 0 until size) {
                for (col in 0 until size) {
                    append(
                        when (cells[row * size + col]) {
                            StoneColor.BLACK -> 'X'
                            StoneColor.WHITE -> 'O'
                            null -> '.'
                        },
                    )
                }
                append('\n')
            }
        }
}
