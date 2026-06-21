package com.example.bluemesh.ui.setup

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluemesh.ui.LocalAccessibility

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accessibility = LocalAccessibility.current
    val repository = remember { com.example.bluemesh.data.DefaultDataRepository.getInstance(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE) }
    var nameInput by remember { mutableStateOf(prefs.getString("display_name", "") ?: "") }
    var showError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Slate Navy
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "BlueMesh",
                fontSize = accessibility.headerFontSize * 1.5f,
                fontWeight = accessibility.headerFontWeight,
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6))
                    )
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Offline Peer-to-Peer Chat",
                color = Color(0xFF94A3B8),
                fontSize = accessibility.bodyFontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Choose your display name",
                color = Color.White,
                fontSize = accessibility.bodyFontSize * 1.1f,
                fontWeight = accessibility.bodyFontWeight,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    if (it.isNotBlank()) showError = false
                },
                placeholder = { Text("Enter display name...", color = Color(0xFF64748B)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF1E293B),
                    cursorColor = Color(0xFF3B82F6)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            )

            if (showError) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Name cannot be blank",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = accessibility.captionFontSize,
                    modifier = Modifier.align(Alignment.Start)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val trimmed = nameInput.trim()
                    if (trimmed.isNotBlank()) {
                        repository.saveDisplayName(trimmed)
                        onSetupComplete()
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Save and Continue",
                        color = Color.White,
                        fontSize = accessibility.bodyFontSize,
                        fontWeight = accessibility.bodyFontWeight
                    )
                }
            }
        }
    }
}
