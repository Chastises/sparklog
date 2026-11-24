package com.example.sparklog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colors taken from the Bjärshögs Elbyrå logo:
//   navy   #304078  (right circle)   indigo #394896 (middle circle)   blue #4F7BBD (left circle, wordmark)
// Light mode uses the navy for buttons and bars with pale blue tints for containers;
// dark mode uses the logo navy and indigo as container colors with lightened blues as accents.
private val Light = lightColorScheme(
    primary = Color(0xFF304078), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E3F5), onPrimaryContainer = Color(0xFF121C40),
    secondary = Color(0xFF394896), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F6), onSecondaryContainer = Color(0xFF1B2B55),
    tertiary = Color(0xFF4A74B5), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E2F8), onTertiaryContainer = Color(0xFF0F2A55),
    background = Color(0xFFF7F9FD), onBackground = Color(0xFF181B24),
    surface = Color(0xFFF7F9FD), onSurface = Color(0xFF181B24),
    surfaceVariant = Color(0xFFE0E6F2), onSurfaceVariant = Color(0xFF444B5C),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainer = Color(0xFFEBEFF7), surfaceContainerHigh = Color(0xFFE5EAF4),
    surfaceContainerHighest = Color(0xFFDFE5F0),
    outline = Color(0xFF737A8C), outlineVariant = Color(0xFFC3CAD9),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB8E8), onPrimary = Color(0xFF14224F),
    primaryContainer = Color(0xFF304078), onPrimaryContainer = Color(0xFFDCE6F8),
    secondary = Color(0xFF8FA0DA), onSecondary = Color(0xFF1B2760),
    secondaryContainer = Color(0xFF394896), onSecondaryContainer = Color(0xFFE0E6FA),
    tertiary = Color(0xFF7FA3DA), onTertiary = Color(0xFF0B2552),
    tertiaryContainer = Color(0xFF2F4F86), onTertiaryContainer = Color(0xFFD3E2F8),
    background = Color(0xFF0F1322), onBackground = Color(0xFFE2E5F0),
    surface = Color(0xFF0F1322), onSurface = Color(0xFFE2E5F0),
    surfaceVariant = Color(0xFF3F465A), onSurfaceVariant = Color(0xFFC3C9DA),
    surfaceContainerLowest = Color(0xFF0A0D19), surfaceContainerLow = Color(0xFF151A2B),
    surfaceContainer = Color(0xFF191E30), surfaceContainerHigh = Color(0xFF232839),
    surfaceContainerHighest = Color(0xFF2E3344),
    outline = Color(0xFF8D93A6), outlineVariant = Color(0xFF3F465A),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SparkLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
