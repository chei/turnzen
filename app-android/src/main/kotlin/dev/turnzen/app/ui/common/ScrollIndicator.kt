package dev.turnzen.app.ui.common

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.turnzen.app.ui.theme.colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * A thin bar at the right edge showing where the list is, the way Android's own lists do it:
 * it appears while the list scrolls and fades out a moment after it stops, with the system's
 * own delay and fade. Drawn only when the content is longer than the screen.
 *
 * Length and position come from the list's measure of itself ([ScrollableState.scrollIndicatorState]).
 * For a lazy list that is an estimate from the rows laid out so far, which is close when the
 * rows are all the same height.
 */
@Composable
fun Modifier.scrollIndicator(state: ScrollableState): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        // Latest: a scroll that starts again during the delay or the fade cancels it.
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                alpha.snapTo(1f)
            } else {
                delay(ViewConfiguration.getScrollDefaultDelay().toLong())
                alpha.animateTo(0f, tween(ViewConfiguration.getScrollBarFadeDuration()))
            }
        }
    }
    val color = colors.ink2
    return drawWithContent {
        drawContent()
        val indicator = state.scrollIndicatorState ?: return@drawWithContent
        val content = indicator.contentSize.toFloat()
        val viewport = indicator.viewportSize.toFloat()
        if (alpha.value == 0f || content <= viewport || viewport <= 0f) return@drawWithContent
        val width = 4.dp.toPx()
        val length = (size.height * viewport / content).coerceAtLeast(32.dp.toPx())
        val travelled = (indicator.scrollOffset / (content - viewport)).coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width - 2.dp.toPx(), (size.height - length) * travelled),
            size = Size(width, length),
            cornerRadius = CornerRadius(width / 2),
            alpha = 0.6f * alpha.value,
        )
    }
}
