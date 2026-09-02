package dev.turnzen.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.turnzen.app.R

val PlexSans =
    FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )

private val lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    size: Int,
    weight: FontWeight,
    lineHeight: Int = size + 6,
) = TextStyle(
    fontFamily = PlexSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    lineHeightStyle = lineHeightStyle,
)

/** Roles from DESIGN.md mapped onto Material 3 slots. */
val TurnzenTypography =
    Typography(
        // Screen title ("Your move", "Settings")
        headlineMedium = style(26, FontWeight.SemiBold, 32),
        // App name on the sign-in screen
        headlineLarge = style(30, FontWeight.SemiBold, 36),
        // Row title / opponent name
        titleMedium = style(16, FontWeight.SemiBold, 22),
        // Body and buttons
        bodyLarge = style(15, FontWeight.Normal, 21),
        labelLarge = style(15, FontWeight.Medium, 21),
        // Secondary line
        bodyMedium = style(13, FontWeight.Normal, 18),
        // Nav label, helper text
        labelSmall = style(12, FontWeight.Normal, 16),
        labelMedium = style(12, FontWeight.Medium, 16),
        bodySmall = style(12, FontWeight.Normal, 16),
        titleSmall = style(14, FontWeight.Medium, 20),
    )
