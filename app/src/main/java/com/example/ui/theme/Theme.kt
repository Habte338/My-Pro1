package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CustomDarkColorScheme = darkColorScheme(
    primary = PolishPurplePrimary,
    onPrimary = PolishPurpleOnPrimary,
    secondary = Color(0xFFEADDFF),
    onSecondary = Color(0xFF21005D),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0F121C), // Deep dark workspace slate
    surface = Color(0xFF1E2436),
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF282F45),
    onSurfaceVariant = Color(0xFF94A3B8)
)

private val CustomLightColorScheme = lightColorScheme(
    primary = PolishPurplePrimary,
    onPrimary = PolishPurpleOnPrimary,
    secondary = PolishPurpleContainer,
    onSecondary = PolishPurpleOnContainer,
    tertiary = Color(0xFFF1F5F9), // Very light tertiary slate
    background = PolishBgLight,
    surface = PolishCardWhite,
    onBackground = PolishTextDark,
    onSurface = PolishTextDark,
    surfaceVariant = PolishTertiaryContainer,
    onSurfaceVariant = PolishTextMuted,
    outline = PolishOutline
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Set false to default to user's desired crisp Light 'Professional Polish' style
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CustomDarkColorScheme else CustomLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
