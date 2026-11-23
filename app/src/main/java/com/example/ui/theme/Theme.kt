package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ZroThemeStyle(val label: String) {
    OBSIDIAN("Obsidian Studio"),
    AMOLED("AMOLED Black"),
    GRAPHITE("Broadcast Graphite")
}

private val ObsidianColorScheme = darkColorScheme(
    primary = ZroCobalt,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFE2E8F0),
    secondary = ZroViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1F242D),
    onSecondaryContainer = Color(0xFFCBD5E1),
    tertiary = ZroEmerald,
    onTertiary = Color(0xFF052E16),
    background = ZroObsidian,
    onBackground = ZroTextPrimary,
    surface = ZroGraphite,
    onSurface = ZroTextPrimary,
    surfaceVariant = ZroGraphiteElevated,
    onSurfaceVariant = ZroTextSecondary,
    surfaceContainerLowest = ZroObsidian,
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = ZroGraphite,
    surfaceContainerHigh = ZroGraphiteElevated,
    surfaceContainerHighest = ZroSlateGlass,
    outline = ZroBorderSubtle,
    outlineVariant = Color(0xFF222732),
    error = ZroLiveRed,
    onError = Color.White,
    errorContainer = Color(0xFF3F1215),
    onErrorContainer = Color(0xFFFECACA)
)

private val AmoledColorScheme = ObsidianColorScheme.copy(
    background = ZroPureBlack,
    surface = Color(0xFF0B0D11),
    surfaceVariant = Color(0xFF13161D),
    surfaceContainerLowest = ZroPureBlack,
    surfaceContainerLow = Color(0xFF080A0D),
    surfaceContainer = Color(0xFF0F1218)
)

private val GraphiteColorScheme = ObsidianColorScheme.copy(
    background = Color(0xFF12151B),
    surface = Color(0xFF191D25),
    surfaceVariant = Color(0xFF212630),
    surfaceContainerLowest = Color(0xFF0E1015),
    surfaceContainerLow = Color(0xFF151921),
    surfaceContainer = Color(0xFF1C212B)
)

val ZroShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun ZroLiveTheme(
    themeStyle: ZroThemeStyle = ZroThemeStyle.OBSIDIAN,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeStyle) {
        ZroThemeStyle.OBSIDIAN -> ObsidianColorScheme
        ZroThemeStyle.AMOLED -> AmoledColorScheme
        ZroThemeStyle.GRAPHITE -> GraphiteColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZroTypography,
        shapes = ZroShapes,
        content = content
    )
}
