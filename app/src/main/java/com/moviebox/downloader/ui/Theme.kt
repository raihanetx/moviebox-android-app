package com.moviebox.downloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* UI v2 "Cinematic Dark" — a single, always-on OLED-friendly palette.
 * Deep near-black surfaces (not gray), a brighter cinema blue for
 * legibility on dark, and the warm amber accent for CTAs. Light scheme
 * is intentionally retired: a media app should look the same everywhere. */

val Amber = Color(0xFFFFC24B)
val CinemaBlue = Color(0xFF5B96FF)

private val DarkScheme = darkColorScheme(
    primary = CinemaBlue,
    onPrimary = Color(0xFF00254F),
    primaryContainer = Color(0xFF1A3A66),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBFC8DC),
    onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDAE2F0),
    tertiary = Amber,
    onTertiary = Color(0xFF3D2A00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFDEA8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0C11),
    onBackground = Color(0xFFE3E6ED),
    surface = Color(0xFF0A0C11),
    onSurface = Color(0xFFE3E6ED),
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFC3C7D1),
    surfaceDim = Color(0xFF0A0C11),
    surfaceBright = Color(0xFF30333B),
    surfaceContainerLowest = Color(0xFF05070B),
    surfaceContainerLow = Color(0xFF12151B),
    surfaceContainer = Color(0xFF171A21),
    surfaceContainerHigh = Color(0xFF1E222A),
    surfaceContainerHighest = Color(0xFF262A33),
    outline = Color(0xFF8D92A0),
    outlineVariant = Color(0xFF3C414D),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE3E6ED),
    inverseOnSurface = Color(0xFF12151B),
    inversePrimary = Color(0xFF1B6BF3),
)

/**
 * MovieBox theme. Always cinematic dark (media apps look best dark and
 * identical on every device); kept as a wrapper so call sites never
 * change. Dynamic color is ignored on purpose — brand over wallpaper.
 */
@Composable
fun MovieBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
