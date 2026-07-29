package com.sa.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SaDarkColorScheme = darkColorScheme(
    primary = SaAccentCyan,
    secondary = SaAccentBlue,
    background = SaNavyBackground,
    surface = SaNavySurface,
    onPrimary = SaNavyBackground,
    onBackground = SaTextPrimary,
    onSurface = SaTextPrimary,
    error = SaErrorRed
)

private val SaLightColorScheme = lightColorScheme(
    primary = SaAccentBlue,
    secondary = SaAccentCyan,
    background = SaLightBackground,
    surface = SaLightSurface,
    onPrimary = SaLightSurface,
    onBackground = SaNavyBackground,
    onSurface = SaNavyBackground,
    error = SaErrorRed
)

@Composable
fun SaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) SaDarkColorScheme else SaLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SaTypography,
        content = content
    )
}
