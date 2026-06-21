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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E))
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
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Accessibility", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1D263B))
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
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatBold,
                                    contentDescription = "Bold Text",
                                    tint = Color(0xFF8B5CF6)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Bold Text", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Use bold font weight for all text", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                        }
                        Switch(
                            checked = boldEnabled,
                            onCheckedChange = { repository.setBoldTextEnabled(it); boldEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF8B5CF6),
                                uncheckedThumbColor = Color(0xFF94A3B8), uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF3B82F6).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TextFields,
                                contentDescription = "Font Size",
                                tint = Color(0xFF3B82F6)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Font Size", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = fontSizeDescriptions.getOrElse(fontSizeLevel.roundToInt() - 1) { "M" },
                                color = Color(0xFF94A3B8),
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
                            thumbColor = Color(0xFF3B82F6),
                            activeTrackColor = Color(0xFF3B82F6),
                            inactiveTrackColor = Color(0xFF334155)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        fontSizeDescriptions.forEach { label ->
                            Text(
                                text = label,
                                color = Color(0xFF64748B),
                                fontSize = 10.sp,
                                fontWeight = if (fontSizeDescriptions.indexOf(label) + 1 == fontSizeLevel.roundToInt()) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Preview: The quick brown fox jumps over the lazy dog",
                        color = Color(0xFF94A3B8),
                        fontSize = (12 + fontSizeLevel.roundToInt() * 2).sp,
                        fontWeight = if (boldEnabled) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
