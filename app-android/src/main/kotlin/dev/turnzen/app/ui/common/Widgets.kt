package dev.turnzen.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.StoneColor

@Composable
fun StoneIcon(
    color: StoneColor,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .background(if (color == StoneColor.BLACK) colors.stoneBlack else colors.stoneWhite, CircleShape)
            .border(1.dp, colors.ink, CircleShape),
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = colors.line)
}

/** A pill-shaped text button. Filled with the accent color when [primary], outlined otherwise. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val background =
        when {
            primary && enabled -> colors.accent
            primary -> colors.line
            else -> Color.Transparent
        }
    val textColor =
        when {
            primary && enabled -> colors.onAccent
            enabled -> colors.ink
            else -> colors.ink2
        }
    Box(
        modifier
            .height(48.dp)
            .background(background, CircleShape)
            .then(if (primary) Modifier else Modifier.border(1.dp, colors.line, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
        )
    }
}

/** Inline text action ("Undo", "Reset", "Sign out"). */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = colors.ink,
    enabled: Boolean = true,
) {
    Text(
        text,
        modifier =
            modifier
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) color else colors.ink2,
    )
}

@Composable
fun ErrorMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = colors.urgent)
}

enum class MainTab { GAMES, SETTINGS }

@Composable
fun TurnzenBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    gamesLabel: String,
    settingsLabel: String,
) {
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .selectableGroup(),
        ) {
            BottomBarItem(
                label = gamesLabel,
                selected = selected == MainTab.GAMES,
                onClick = { onSelect(MainTab.GAMES) },
                modifier = Modifier.weight(1f),
            ) { color ->
                Canvas(Modifier.size(28.dp)) {
                    val r = size.minDimension * 0.25f
                    drawCircle(color, r, Offset(size.width * 0.35f, size.height * 0.62f))
                    drawCircle(color, r, Offset(size.width * 0.66f, size.height * 0.4f), style = Stroke(1.6.dp.toPx()))
                }
            }
            BottomBarItem(
                label = settingsLabel,
                selected = selected == MainTab.SETTINGS,
                onClick = { onSelect(MainTab.SETTINGS) },
                modifier = Modifier.weight(1f),
            ) { color ->
                Canvas(Modifier.size(28.dp)) {
                    drawCircle(color, size.minDimension * 0.3f, style = Stroke(1.6.dp.toPx()))
                    drawCircle(color, size.minDimension * 0.09f)
                }
            }
        }
        Box(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun BottomBarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val color = if (selected) colors.accent else colors.ink2
    Column(
        modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(color)
        Text(
            label,
            style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
