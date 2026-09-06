package dev.turnzen.app.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
 * The board, drawn on a Canvas. Square, full width; grid spacing is width / size with the
 * first line half a cell from the edge (DESIGN.md).
 *
 * [position] is what is drawn; [trialMoves] are the stones within it that belong to the
 * trial variation (numbered, ringed). Tapping calls [onTap] with the intersection and
 * nothing else: this composable never talks to a server.
 */
@Composable
fun GoBoard(
    position: Position,
    modifier: Modifier = Modifier,
    trialMoves: List<ColoredMove> = emptyList(),
    settings: BoardSettings = BoardSettings(),
    enabled: Boolean = true,
    onTap: (Point) -> Unit = {},
) {
    val n = position.size
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
                    onTap(Point(col, row))
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

        for ((point, color) in position.board.stones()) {
            val trialNumber = trialPoints[point]
            val c = center(point)
            if (trialNumber == null) {
                drawStone(c, radius, color, palette)
            } else {
                drawStone(c, radius, color, palette, alpha = 0.9f)
                drawCircle(palette.accent, radius + 1.2.dp.toPx(), c, style = Stroke(1.6.dp.toPx()))
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
                }
            }
        }

        if (settings.markLastMove && !trialMode) {
            val last = position.lastMove
            val lastPoint = last?.point
            if (last != null && lastPoint != null && position.board[lastPoint] == last.color) {
                val dot = if (last.color == StoneColor.BLACK) palette.stoneWhite else palette.stoneBlack
                drawCircle(dot, radius * 0.28f, center(lastPoint))
            }
        }

        if (trialMode) {
            val edge = 3.dp.toPx()
            drawRect(palette.accent, Offset.Zero, Size(size.width, edge))
            drawRect(palette.accent, Offset(0f, size.height - edge), Size(size.width, edge))
        }
    }
}

private fun DrawScope.drawStone(
    center: Offset,
    radius: Float,
    color: StoneColor,
    palette: TurnzenColors,
    alpha: Float = 1f,
) {
    val fill: Color = if (color == StoneColor.BLACK) palette.stoneBlack else palette.stoneWhite
    drawCircle(fill, radius, center, alpha = alpha)
    if (color ==
        StoneColor.WHITE
    ) {
        drawCircle(palette.stoneBlack, radius, center, alpha = alpha, style = Stroke(0.6.dp.toPx()))
    }
}
