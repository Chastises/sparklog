package com.example.sparklog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Deep blue with an amber accent. Surfaces are set explicitly so nothing picks up a purple tint.
private val Light = lightColorScheme(
    primary = Color(0xFF1B4F8F), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF8A6100), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7A8), onSecondaryContainer = Color(0xFF2B1D00),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E5EE), onSurfaceVariant = Color(0xFF444851),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F4F9),
    surfaceContainer = Color(0xFFECEFF5), surfaceContainerHigh = Color(0xFFE6E9F0),
    surfaceContainerHighest = Color(0xFFE0E4EC),
    outline = Color(0xFF747882), outlineVariant = Color(0xFFC4C8D2),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF00468C), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFF2C14E), onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5A4000), onSecondaryContainer = Color(0xFFFFE7A8),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF444851), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C21),
    surfaceContainer = Color(0xFF1D2025), surfaceContainerHigh = Color(0xFF272A30),
    surfaceContainerHighest = Color(0xFF32353B),
    outline = Color(0xFF8E919B), outlineVariant = Color(0xFF444851),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SparkLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
