package com.example.bluemesh.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    )
)

private val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFF10B981),
    background = Color(0xFF0E131E),
    surface = Color(0xFF1D263B),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFEF4444),
    outline = Color(0xFF334155),
)

private val LightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF8B5CF6),
    tertiary = Color(0xFF10B981),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A1D23),
    onSurface = Color(0xFF1A1D23),
    error = Color(0xFFEF4444),
    outline = Color(0xFFE5E7EB),
)

@Composable
fun BlueMeshTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val blueMeshColors = if (darkTheme) BlueMeshColors.Dark else BlueMeshColors.Light

    CompositionLocalProvider(LocalBlueMeshColors provides blueMeshColors) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}
