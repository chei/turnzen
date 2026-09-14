package dev.turnzen.app.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.turnzen.app.data.BoardSettings
import dev.turnzen.app.ui.theme.PlexSans
import dev.turnzen.app.ui.theme.TurnzenColors
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.BoardSizes
import dev.turnzen.game.ColoredMove
import dev.turnzen.game.Coordinates
import dev.turnzen.game.Point
import dev.turnzen.game.Position
import dev.turnzen.game.StoneColor

/**
 * How far the finger travels for one move of the timeline.
 *
 * A fixed rate rather than a mapping from the board's width, so the gesture feels the same in
 * a 40-move 9x9 and a 250-move 19x19; the ends of the timeline are the skip buttons' job.
 * Small enough that flicking back a move or two is a short movement, large enough that a stone
 * placed with a slightly unsteady finger is still a stone.
 */
private val SCRUB_PER_MOVE = 12.dp

/**
 * What a board being counted shows on top of the stones: the ones both players are treating
 * as dead, and the empty points each side has ended up surrounding.
 */
data class ScoringOverlay(
    val dead: Set<Point> = emptySet(),
    val blackTerritory: Set<Point> = emptySet(),
    val whiteTerritory: Set<Point> = emptySet(),
)

/**
 * The board, drawn on a Canvas. Square, full width; grid spacing is width / size with the
 * first line half a cell from the edge (DESIGN.md).
 *
 * [position] is what is drawn; [trialMoves] are the stones within it that belong to the
 * trial variation (numbered, or the most recent one dotted when numbering is off). Tapping
 * calls [onTap] with the intersection and nothing else: this composable never talks to a server.
 *
 * A horizontal drag walks the game's timeline through [onScrub] — right towards the live
 * position, left towards the opening, matching the way the navigation buttons are laid out.
 * It is safe to have both gestures on one surface because they are told apart by *distance*:
 * a touch that never travels [SCRUB_PER_MOVE]'s worth of slop stays a tap and places its stone
 * with no delay. (A double tap could not be added on the same terms — it is told apart by
 * *time*, which would hold every single tap for the double-tap timeout.)
 */
@Composable
fun GoBoard(
    position: Position,
    modifier: Modifier = Modifier,
    trialMoves: List<ColoredMove> = emptyList(),
    settings: BoardSettings = BoardSettings(),
    enabled: Boolean = true,
    /** Non-null while the game is being counted; see [ScoringOverlay]. */
    scoring: ScoringOverlay? = null,
    onTap: (Point) -> Unit = {},
    /** Whole moves crossed since the last call; positive walks towards the live position. */
    onScrub: ((Int) -> Unit)? = null,
) {
    val n = position.size
    // Read through a holder for the same reason the move-navigation buttons do: the tap block
    // below is captured by the pointer-input node and refreshed only when the keys change. Two
    // games of the same size running one after the other keep the same keys, so a board that
    // closed over [onTap] would go on placing trial stones in the game the user skipped away
    // from; the same applies when a game enters the counting phase and [onTap] changes meaning.
    val tap by rememberUpdatedState(onTap)
    val scrub by rememberUpdatedState(onScrub)
    val textMeasurer = rememberTextMeasurer()
    val palette = colors
    val trialPoints = trialMoves.mapIndexedNotNull { i, m -> m.point?.let { it to i + 1 } }.toMap()
    val trialMode = trialMoves.isNotEmpty()

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(n, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val cell = size.width.toFloat() / n
                    val col = (offset.x / cell).toInt().coerceIn(0, n - 1)
                    val row = (offset.y / cell).toInt().coerceIn(0, n - 1)
                    tap(Point(col, row))
                }
            }
            // After the tap detector in the chain, so this one sees the main pass first and
            // can claim the gesture once it is a drag; that claim is what cancels the pending
            // tap, so a scrub never leaves a stone behind it.
            .pointerInput(enabled, onScrub != null) {
                if (!enabled || scrub == null) return@pointerInput
                val perMove = SCRUB_PER_MOVE.toPx()
                // Distance the finger has travelled but not yet spent on a move, so a slow
                // drag crosses moves at the same rate as a fast one instead of rounding each
                // event's few pixels away to nothing.
                var carried = 0f
                detectHorizontalDragGestures(
                    onDragStart = { carried = 0f },
                    onDragEnd = { carried = 0f },
                    onDragCancel = { carried = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    carried += dragAmount
                    val steps = (carried / perMove).toInt()
                    if (steps != 0) {
                        carried -= steps * perMove
                        scrub?.invoke(steps)
                    }
                }
            },
    ) {
        val cell = size.width / n
        val off = cell / 2
        val radius = cell * 0.46f

        fun center(p: Point) = Offset(off + p.col * cell, off + p.row * cell)

        drawRect(palette.board)

        val lineWidth = maxOf(0.8.dp.toPx(), 1f)
        for (i in 0 until n) {
            val p = off + i * cell
            drawLine(palette.boardLine, Offset(off, p), Offset(size.width - off, p), lineWidth)
            drawLine(palette.boardLine, Offset(p, off), Offset(p, size.height - off), lineWidth)
        }
        for (h in BoardSizes.hoshi(n)) drawCircle(palette.boardLine, cell * 0.1f, center(h))

        if (settings.showCoordinates) {
            val fontPx = maxOf(8.sp.toPx(), cell * 0.32f)
            val style =
                TextStyle(fontFamily = PlexSans, fontSize = (fontPx / 1.sp.toPx()).sp, color = palette.boardLine)
            for (i in 0 until n) {
                val letter = textMeasurer.measure(Coordinates.columnLetter(i).toString(), style)
                drawText(
                    letter,
                    topLeft =
                        Offset(
                            off + i * cell - letter.size.width / 2f,
                            size.height - letter.size.height - 1.dp.toPx(),
                        ),
                )
                val number = textMeasurer.measure((n - i).toString(), style)
                drawText(number, topLeft = Offset(2.dp.toPx(), off + i * cell - number.size.height / 2f))
            }
        }

        // Trial stones look like any other stone. When numbering is on, the numbers are
        // their only marking; when it is off, only the most recently played trial stone is
        // marked, with the same dot the last real move uses.
        val lastTrialPoint = trialMoves.lastOrNull { it.point != null }?.point
        for ((point, color) in position.board.stones()) {
            val trialNumber = trialPoints[point]
            val c = center(point)
            // A stone marked dead is drawn as a ghost of itself: it is still on the board, and
            // the mark has to be as easy to take back as it was to make.
            val dead = scoring != null && point in scoring.dead
            drawStone(c, radius, color, palette, alpha = if (dead) DEAD_STONE_ALPHA else 1f)
            if (trialNumber != null) {
                if (settings.numberTrialStones) {
                    val style =
                        TextStyle(
                            fontFamily = PlexSans,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = (radius * 1.15f / 1.sp.toPx()).sp,
                            color = if (color == StoneColor.BLACK) palette.stoneWhite else palette.stoneBlack,
                        )
                    val label = textMeasurer.measure(trialNumber.toString(), style)
                    drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
                } else if (point == lastTrialPoint) {
                    val dot = if (color == StoneColor.BLACK) palette.stoneWhite else palette.stoneBlack
                    drawCircle(dot, radius * 0.28f, c)
                }
            }
        }

        if (scoring != null) {
            // Territory as a small square in the owner's colour: the same vocabulary as the
            // stones, at a size that cannot be mistaken for one.
            val side = radius * 0.62f
            for (point in scoring.blackTerritory) drawTerritory(center(point), side, StoneColor.BLACK, palette)
            for (point in scoring.whiteTerritory) drawTerritory(center(point), side, StoneColor.WHITE, palette)
        }

        if (settings.markLastMove && !trialMode && scoring == null) {
            val last = position.lastMove
            val lastPoint = last?.point
            if (last != null && lastPoint != null && position.board[lastPoint] == last.color) {
                val dot = if (last.color == StoneColor.BLACK) palette.stoneWhite else palette.stoneBlack
                drawCircle(dot, radius * 0.28f, center(lastPoint))
            }
        }
    }
}

/** How much of a stone is left showing once it has been marked dead. */
private const val DEAD_STONE_ALPHA = 0.32f

private fun DrawScope.drawStone(
    center: Offset,
    radius: Float,
    color: StoneColor,
    palette: TurnzenColors,
    alpha: Float = 1f,
) {
    val fill: Color = if (color == StoneColor.BLACK) palette.stoneBlack else palette.stoneWhite
    drawCircle(fill, radius, center, alpha = alpha)
    if (color == StoneColor.WHITE) {
        drawCircle(palette.stoneBlack, radius, center, alpha = alpha, style = Stroke(0.6.dp.toPx()))
    }
}

private fun DrawScope.drawTerritory(
    center: Offset,
    side: Float,
    color: StoneColor,
    palette: TurnzenColors,
) {
    val fill = if (color == StoneColor.BLACK) palette.stoneBlack else palette.stoneWhite
    val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
    drawRect(fill, topLeft, Size(side, side))
    if (color == StoneColor.WHITE) {
        drawRect(palette.stoneBlack, topLeft, Size(side, side), style = Stroke(0.6.dp.toPx()))
    }
}
