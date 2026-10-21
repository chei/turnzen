package dev.turnzen.app.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.turnzen.app.ui.theme.TurnzenColors
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor

/*
 * How the board's wood and its stones are painted, in one place: by the board itself and by
 * every small picture of a stone on the board elsewhere ([StoneOnBoard]). Board and stone
 * skins are meant to come; they belong here, so that a stone outside the board changes
 * along with the ones on it.
 */

/** The board's surface, under the lines. */
internal fun DrawScope.drawBoardSurface(palette: TurnzenColors) {
    drawRect(palette.board)
}

/** One of the board's grid lines, from [start] to [end]. */
internal fun DrawScope.drawBoardLine(
    start: Offset,
    end: Offset,
    palette: TurnzenColors,
) {
    drawLine(palette.boardLine, start, end, boardLineWidth())
}

private fun DrawScope.boardLineWidth(): Float = maxOf(0.8.dp.toPx(), 1f)

internal fun DrawScope.drawStone(
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

/**
 * A stone on a point of the board: how a player's colour is shown beside their name. On the
 * page alone a black disc and an outlined one read as a pair of radio buttons; on the wood,
 * sitting on the crossing of two lines, they read as stones.
 *
 * Corners as softened as a player icon's, which it sits next to. The square is barely larger
 * than the stone: enough wood round it to read as a board, and the lines' ends showing.
 */
@Composable
fun StoneOnBoard(
    color: StoneColor,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = colors
    Canvas(modifier.size(size).clip(RoundedCornerShape(4.dp))) {
        drawBoardSurface(palette)
        drawBoardLine(Offset(0f, center.y), Offset(this.size.width, center.y), palette)
        drawBoardLine(Offset(center.x, 0f), Offset(center.x, this.size.height), palette)
        drawStone(center, this.size.minDimension * STONE_SHARE / 2f, color, palette)
    }
}

private const val STONE_SHARE = 0.8f
