package com.example.bluemesh.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class BlueMeshColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val divider: Color,
    val switchUncheckedThumb: Color,
    val switchUncheckedTrack: Color,
    val drawerSurface: Color,
) {
    companion object {
        val Dark = BlueMeshColors(
            background = Color(0xFF0E131E),
            surface = Color(0xFF1D263B),
            surfaceVariant = Color(0xFF1E293B),
            onBackground = Color.White,
            onSurface = Color.White,
            textSecondary = Color(0xFF94A3B8),
            textTertiary = Color(0xFF64748B),
            primary = Color(0xFF3B82F6),
            primaryContainer = Color(0xFF3B82F6).copy(alpha = 0.2f),
            secondary = Color(0xFF8B5CF6),
            secondaryContainer = Color(0xFF8B5CF6).copy(alpha = 0.2f),
            success = Color(0xFF10B981),
            warning = Color(0xFFF59E0B),
            error = Color(0xFFEF4444),
            info = Color(0xFF38BDF8),
            divider = Color(0xFF334155),
            switchUncheckedThumb = Color(0xFF94A3B8),
            switchUncheckedTrack = Color(0xFF334155),
            drawerSurface = Color(0xFF1D263B),
        )

        val Light = BlueMeshColors(
            background = Color(0xFFF7F8FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFF0F1F3),
            onBackground = Color(0xFF1A1D23),
            onSurface = Color(0xFF1A1D23),
            textSecondary = Color(0xFF6B7280),
            textTertiary = Color(0xFF9CA3AF),
            primary = Color(0xFF3B82F6),
            primaryContainer = Color(0xFF3B82F6).copy(alpha = 0.15f),
            secondary = Color(0xFF8B5CF6),
            secondaryContainer = Color(0xFF8B5CF6).copy(alpha = 0.15f),
            success = Color(0xFF10B981),
            warning = Color(0xFFF59E0B),
            error = Color(0xFFEF4444),
            info = Color(0xFF38BDF8),
            divider = Color(0xFFE5E7EB),
            switchUncheckedThumb = Color(0xFFD1D5DB),
            switchUncheckedTrack = Color(0xFFE5E7EB),
            drawerSurface = Color.White,
        )
    }
}

val LocalBlueMeshColors = staticCompositionLocalOf { BlueMeshColors.Dark }

val LocalIsDarkMode = staticCompositionLocalOf { true }
val LocalOnThemeToggle = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }
