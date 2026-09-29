package dev.turnzen.game

sealed interface TrialOutcome {
    data class Placed(
        val variation: TrialVariation,
    ) : TrialOutcome

    data class Rejected(
        val reason: IllegalMoveReason,
    ) : TrialOutcome
}

/**
 * Local "what if" stones on top of a server position. Colors alternate starting with
 * [firstColor]. Immutable: every action returns a new variation, which fits a UiState.
 *
 * Only the first move is ever sent to a server; the rest exists purely for thinking.
 */
class TrialVariation private constructor(
    val base: Position,
    val firstColor: StoneColor,
    private val steps: List<Step>,
) {
    private data class Step(
        val move: ColoredMove,
        val position: Position,
    )

    companion object {
        fun start(
            base: Position,
            firstColor: StoneColor = base.toMove,
        ): TrialVariation = TrialVariation(base, firstColor, emptyList())
    }

    val moves: List<ColoredMove>
        get() = steps.map { it.move }

    val position: Position
        get() = steps.lastOrNull()?.position ?: base

    /** Positions along the variation: index 0 is [base], index i is after i trial moves. */
    val positions: List<Position>
        get() =
            buildList {
                add(base)
                steps.forEach { add(it.position) }
            }

    /** The position after the first [depth] trial moves (0 = [base]). */
    fun positionAt(depth: Int): Position = positions[depth.coerceIn(0, steps.size)]

    /** A copy keeping only the first [depth] trial moves; used to branch a new line. */
    fun truncatedTo(depth: Int): TrialVariation =
        TrialVariation(base, firstColor, steps.take(depth.coerceIn(0, steps.size)))

    val size: Int
        get() = steps.size

    val isEmpty: Boolean
        get() = steps.isEmpty()

    val nextColor: StoneColor
        get() =
            steps
                .lastOrNull()
                ?.move
                ?.color
                ?.opposite ?: firstColor

    /** The move that would be sent to the server: the first stone of this variation. */
    val firstMove: ColoredMove?
        get() = steps.firstOrNull()?.move

    fun place(point: Point): TrialOutcome =
        when (val result = Rules.play(position, nextColor, Move.Play(point))) {
            is PlayResult.Legal -> {
                TrialOutcome.Placed(
                    TrialVariation(
                        base,
                        firstColor,
                        steps + Step(ColoredMove(nextColor, Move.Play(point)), result.position),
                    ),
                )
            }

            is PlayResult.Illegal -> {
                TrialOutcome.Rejected(result.reason)
            }
        }

    fun pass(): TrialVariation {
        val result = Rules.play(position, nextColor, Move.Pass) as PlayResult.Legal
        return TrialVariation(base, firstColor, steps + Step(ColoredMove(nextColor, Move.Pass), result.position))
    }

    fun undo(): TrialVariation = if (steps.isEmpty()) this else TrialVariation(base, firstColor, steps.dropLast(1))

    fun reset(): TrialVariation = TrialVariation(base, firstColor, emptyList())
}
