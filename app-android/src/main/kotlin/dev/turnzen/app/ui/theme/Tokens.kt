package dev.turnzen.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colour palette, provided through [LocalTurnzenColors] so it can switch between
 * light and dark at runtime. Board and stone colours are identical in both themes
 * (DESIGN.md); only surface/ink/line change, plus accent and urgent, which are lightened in
 * dark so they stay legible against a dark surface.
 */
data class TurnzenColors(
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val urgent: Color,
    val board: Color,
    val boardLine: Color,
    val stoneBlack: Color,
    val stoneWhite: Color,
    val onAccent: Color,
)

val LightColors =
    TurnzenColors(
        surface = Color(0xFFF2F3F0),
        ink = Color(0xFF1F2A26),
        ink2 = Color(0xFF6B7470),
        line = Color(0xFFD6DAD5),
        accent = Color(0xFF2E4A7D),
        accentSoft = Color(0xFFE4E9F2),
        urgent = Color(0xFFB8483E),
        board = Color(0xFFD9B778),
        boardLine = Color(0xFF5A4A2E),
        stoneBlack = Color(0xFF15171A),
        stoneWhite = Color(0xFFF7F5F0),
        onAccent = Color(0xFFFFFFFF),
    )

val DarkColors =
    TurnzenColors(
        surface = Color(0xFF15191B),
        ink = Color(0xFFE9ECE8),
        ink2 = Color(0xFF97A09C),
        line = Color(0xFF2B302E),
        accent = Color(0xFF93A9D6),
        accentSoft = Color(0xFF243049),
        urgent = Color(0xFFDB7B70),
        board = Color(0xFFD9B778),
        boardLine = Color(0xFF5A4A2E),
        stoneBlack = Color(0xFF15171A),
        stoneWhite = Color(0xFFF7F5F0),
        onAccent = Color(0xFF14181A),
    )

val LocalTurnzenColors = staticCompositionLocalOf { LightColors }

/** Shorthand for the current palette inside composables: `colors.ink`, `colors.accent`, ... */
val colors: TurnzenColors
    @Composable
    @ReadOnlyComposable
    get() = LocalTurnzenColors.current
