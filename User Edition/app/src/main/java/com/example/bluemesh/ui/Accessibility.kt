package com.example.bluemesh.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

data class AccessibilityState(
    val boldEnabled: Boolean = false,
    val fontSizeLevel: Int = 2,
    val messageFontSize: TextUnit = 15.sp,
    val headerFontSize: TextUnit = 24.sp,
    val subtitleFontSize: TextUnit = 13.sp,
    val bodyFontSize: TextUnit = 15.sp,
    val captionFontSize: TextUnit = 11.sp,
    val chatInputFontSize: TextUnit = 15.sp,
    val fontWeight: FontWeight = FontWeight.Normal,
    val headerFontWeight: FontWeight = FontWeight.Bold,
    val bodyFontWeight: FontWeight = FontWeight.SemiBold,
    val subtitleFontWeight: FontWeight = FontWeight.Normal,
    val captionFontWeight: FontWeight = FontWeight.Normal
)

fun computeAccessibilityState(boldEnabled: Boolean, fontSizeLevel: Int): AccessibilityState {
    val level = fontSizeLevel.coerceIn(1, 7)
    val baseFontSize = (11 + level * 2).sp
    return AccessibilityState(
        boldEnabled = boldEnabled,
        fontSizeLevel = level,
        messageFontSize = baseFontSize,
        headerFontSize = (18 + level * 2).sp,
        subtitleFontSize = (9 + level * 2).sp,
        bodyFontSize = baseFontSize,
        captionFontSize = (7 + level * 2).sp,
        chatInputFontSize = baseFontSize,
        fontWeight = if (boldEnabled) FontWeight.Bold else FontWeight.Normal,
        headerFontWeight = if (boldEnabled) FontWeight.ExtraBold else FontWeight.Bold,
        bodyFontWeight = if (boldEnabled) FontWeight.Bold else FontWeight.SemiBold,
        subtitleFontWeight = if (boldEnabled) FontWeight.Medium else FontWeight.Normal,
        captionFontWeight = if (boldEnabled) FontWeight.Medium else FontWeight.Normal
    )
}

val LocalAccessibility = compositionLocalOf { AccessibilityState() }
