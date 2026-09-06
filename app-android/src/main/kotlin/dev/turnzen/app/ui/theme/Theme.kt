package dev.turnzen.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private fun materialScheme(
    c: TurnzenColors,
    dark: Boolean,
) = if (dark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accent,
        secondary = c.ink2,
        onSecondary = c.surface,
        background = c.surface,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surface,
        onSurfaceVariant = c.ink2,
        outline = c.line,
        outlineVariant = c.line,
        error = c.urgent,
        onError = c.onAccent,
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accent,
        secondary = c.ink2,
        onSecondary = c.surface,
        background = c.surface,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surface,
        onSurfaceVariant = c.ink2,
        outline = c.line,
        outlineVariant = c.line,
        error = c.urgent,
        onError = c.onAccent,
    )
}

/**
 * Applies the palette for [darkTheme] and keeps the system bar icons legible against it:
 * dark icons on the light surface, light icons on the dark one. The board colours are the
 * same in both themes (DESIGN.md).
 */
@Composable
fun TurnzenTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalTurnzenColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme(colors, darkTheme),
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
}
