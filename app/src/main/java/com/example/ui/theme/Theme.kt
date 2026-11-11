package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CleanLightColorScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlueLight,
    onPrimaryContainer = AccentBlueDark,
    secondary = StatusGreen,
    onSecondary = Color.White,
    secondaryContainer = StatusGreenLight,
    onSecondaryContainer = Color(0xFF14532D),
    tertiary = StatusAmber,
    onTertiary = Color.White,
    background = SoftGrayBg,
    onBackground = InkPrimary,
    surface = CleanWhite,
    onSurface = InkPrimary,
    surfaceVariant = SoftSurfaceVariant,
    onSurfaceVariant = InkSecondary,
    outline = SoftCardBorder,
    error = StatusRed,
    onError = Color.White
)

@Composable
fun NEMITheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CleanLightColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    NEMITheme(darkTheme = darkTheme, content = content)
}
