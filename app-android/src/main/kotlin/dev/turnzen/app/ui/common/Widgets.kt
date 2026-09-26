package dev.turnzen.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.turnzen.app.R
import dev.turnzen.app.ui.theme.colors
import dev.turnzen.game.GameResult
import dev.turnzen.game.ResultReason
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.RemainingTime

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
            .clip(CircleShape)
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

/**
 * A server problem on a screen that still has content of its own: one line of copy, quiet
 * ([colors.ink2]) for something to wait out and urgent for a real error, plus the retry the
 * copy promises when the screen can offer one. Pass [onRetry] as null where the screen
 * already has a retry gesture — pull-to-refresh on the games list, Send on the board.
 */
@Composable
fun ProblemMessage(
    problem: ServerProblem,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    /** What [onRetry] is called on screen; "Try again" unless the way out is something else. */
    retryLabel: String? = null,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A problem about one server of several carries that server's mark.
            problem.server?.let {
                ServerDot(it)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (problem.transient) "${problem.title}. ${problem.message}" else problem.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem.transient) colors.ink2 else colors.urgent,
            )
        }
        if (onRetry != null) {
            TextAction(retryLabel ?: stringResource(R.string.action_retry), onRetry, color = colors.accent)
        }
    }
}

/**
 * A server problem filling a screen that has nothing else to show — the games list before it
 * has ever loaded. Built like the empty state next to it rather than like an error: title,
 * explanation, and the action that resolves it. No icon; the offline pictograms (a crossed
 * cloud, a struck-through signal) belong to a different vocabulary than the stones and rings
 * used everywhere else here, and the sentence says it more precisely anyway.
 */
@Composable
fun ProblemState(
    problem: ServerProblem,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 160.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            problem.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            problem.message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PillButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            modifier = Modifier.width(180.dp),
        )
    }
}

/**
 * The remaining-time figure, coloured by how close the game is to timing out. Under three
 * days it turns urgent red (matching DGS); under twelve hours it also sits on a soft red
 * pill and goes bold, so an almost-lost game stands out at a glance. One colour throughout,
 * only the emphasis grows (DESIGN.md). Shared by the games list and the board header so the
 * two read identically.
 *
 * On the opponent's turn ([muted]) it is instead shown quietly in [colors.ink2] with no
 * weight or urgency: the figure is your own clock, which is paused until they move, so it
 * carries information without competing with the games actually waiting on you. The mute is
 * what distinguishes a their-turn row — no "their move" caption needed.
 */
@Composable
fun RemainingTimeLabel(
    time: RemainingTime?,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    if (muted) {
        Text(
            Formatting.remaining(time),
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink2,
        )
        return
    }
    val urgency = Formatting.urgency(time)
    val critical = urgency == Urgency.CRITICAL
    val pill =
        if (critical) {
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.urgent.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        } else {
            modifier
        }
    Text(
        Formatting.remaining(time),
        modifier = pill,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (critical) FontWeight.Bold else FontWeight.SemiBold,
        color = if (urgency == Urgency.NONE) colors.ink else colors.urgent,
    )
}

/**
 * How a finished game went, from the reader's seat: "Won by 12.5" on one line, and under it
 * what the game did to their rating. Their own result first — your own games are read for who
 * won, not for which colour did. Takes the place a running game's clock has, on the list and
 * in the board header alike.
 */
@Composable
fun OutcomeLabel(
    result: GameResult?,
    you: StoneColor,
    ratingChange: Double? = null,
    modifier: Modifier = Modifier,
) {
    val won = result?.winner == you
    val detail = outcomeDetail(result)
    val headline =
        when {
            result == null || result.by == ResultReason.NO_RESULT -> stringResource(R.string.score_no_result)
            result.winner == null -> stringResource(R.string.score_draw)
            won && detail != null -> stringResource(R.string.games_won_by, detail)
            won -> stringResource(R.string.games_won)
            detail != null -> stringResource(R.string.games_lost_by, detail)
            else -> stringResource(R.string.games_lost)
        }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            headline,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            // The accent marks the reader's own wins; everything else stays quiet. A loss is
            // not an alarm, so it is never the urgent colour.
            color = if (won) colors.accent else colors.ink2,
        )
        ratingChange?.let {
            Text(
                stringResource(R.string.games_rating_points, Formatting.ratingPoints(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/** "by 7.5", "by resignation", "on time" — the second line of an outcome, or nothing. */
@Composable
fun outcomeDetail(result: GameResult?): String? =
    when (result?.by) {
        null, ResultReason.NO_RESULT, ResultReason.JIGO -> null
        ResultReason.RESIGNATION -> stringResource(R.string.games_by_resignation)
        ResultReason.TIMEOUT -> stringResource(R.string.games_on_time)
        ResultReason.FORFEIT -> stringResource(R.string.games_by_forfeit)
        ResultReason.POINTS -> result.margin?.let { stringResource(R.string.games_by_points, Formatting.points(it)) }
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
