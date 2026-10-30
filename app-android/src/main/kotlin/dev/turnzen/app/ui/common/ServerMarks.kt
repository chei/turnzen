package dev.turnzen.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.goserver.ServerId

/**
 * The colour that marks [server] everywhere a game or an account appears. The UI knows
 * *which* server something is on — by its opaque id — and never how to talk to it.
 *
 * Shown even when only one server is signed in: then it is a quiet accent that costs
 * nothing, and signing in to the second server makes it mean something without anything
 * else on screen changing.
 */
@Composable
fun serverColor(server: ServerId?): Color =
    when (server?.value) {
        "ogs" -> colors.serverOgs
        "dgs" -> colors.serverDgs
        else -> colors.line
    }

/** The mark on a list row: a 4dp rounded bar at its leading edge. */
@Composable
fun ServerBar(
    server: ServerId,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    Box(
        modifier
            .size(width = 4.dp, height = height)
            .background(serverColor(server), RoundedCornerShape(2.dp)),
    )
}

/** Where a list row's dot sits: centred in the 20dp margin before the icon or text. */
val DOT_INSET = 6.dp

/** The mark beside a name or a heading, or in a list row's margin: a small dot. */
@Composable
fun ServerDot(
    server: ServerId,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Box(modifier.size(size).background(serverColor(server), CircleShape))
}
