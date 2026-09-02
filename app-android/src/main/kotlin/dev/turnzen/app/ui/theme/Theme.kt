package dev.turnzen.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors =
    lightColorScheme(
        primary = Tokens.accent,
        onPrimary = Tokens.onAccent,
        primaryContainer = Tokens.accentSoft,
        onPrimaryContainer = Tokens.accent,
        secondary = Tokens.ink2,
        onSecondary = Tokens.surface,
        background = Tokens.surface,
        onBackground = Tokens.ink,
        surface = Tokens.surface,
        onSurface = Tokens.ink,
        surfaceVariant = Tokens.surface,
        onSurfaceVariant = Tokens.ink2,
        surfaceContainer = Tokens.surface,
        surfaceContainerHigh = Tokens.surface,
        surfaceContainerHighest = Tokens.line,
        surfaceContainerLow = Tokens.surface,
        surfaceContainerLowest = Tokens.surface,
        outline = Tokens.line,
        outlineVariant = Tokens.line,
        error = Tokens.urgent,
        onError = Tokens.onAccent,
    )

/** Dark theme is not designed yet (DESIGN.md); the light palette is used everywhere. */
@Composable
fun TurnzenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = TurnzenTypography,
        shapes =
            Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
            ),
        content = content,
    )
}
