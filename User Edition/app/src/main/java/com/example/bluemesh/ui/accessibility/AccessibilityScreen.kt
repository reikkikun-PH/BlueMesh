package com.example.bluemesh.ui.accessibility

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.theme.LocalBlueMeshColors
import kotlin.math.roundToInt

@Composable
fun AccessibilityScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { DefaultDataRepository.getInstance(context.applicationContext) }
    var boldEnabled by remember { mutableStateOf(repository.isBoldTextEnabled()) }
    var fontSizeLevel by remember { mutableStateOf(repository.getFontSizeLevel().toFloat()) }
    val fontSizeDescriptions = listOf("XS", "S", "M", "L", "XL", "XXL", "XXXL")
    val colors = LocalBlueMeshColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface)
                ) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Accessibility", color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(colors.secondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatBold,
                                    contentDescription = "Bold Text",
                                    tint = colors.secondary
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Bold Text", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Use bold font weight for all text", color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        Switch(
                            checked = boldEnabled,
                            onCheckedChange = { repository.setBoldTextEnabled(it); boldEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = colors.secondary,
                                uncheckedThumbColor = colors.textSecondary, uncheckedTrackColor = colors.divider
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = colors.divider)
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(colors.primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TextFields,
                                contentDescription = "Font Size",
                                tint = colors.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Font Size", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = fontSizeDescriptions.getOrElse(fontSizeLevel.roundToInt() - 1) { "M" },
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Slider(
                        value = fontSizeLevel,
                        onValueChange = { fontSizeLevel = it },
                        onValueChangeFinished = {
                            val level = fontSizeLevel.roundToInt().coerceIn(1, 7)
                            fontSizeLevel = level.toFloat()
                            repository.setFontSizeLevel(level)
                        },
                        valueRange = 1f..7f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primary,
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.divider
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        fontSizeDescriptions.forEach { label ->
                            Text(
                                text = label,
                                color = colors.textTertiary,
                                fontSize = 10.sp,
                                fontWeight = if (fontSizeDescriptions.indexOf(label) + 1 == fontSizeLevel.roundToInt()) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Preview: The quick brown fox jumps over the lazy dog",
                        color = colors.textSecondary,
                        fontSize = (12 + fontSizeLevel.roundToInt() * 2).sp,
                        fontWeight = if (boldEnabled) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
