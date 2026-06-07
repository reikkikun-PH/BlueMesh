package com.example.bitchat_lite.ui.lock

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bitchat_lite.data.DefaultDataRepository

@Composable
fun LockScreen(
    mode: String,
    onSuccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { DefaultDataRepository.getInstance(context) }
    
    var pinInput by remember { mutableStateOf("") }
    var tempPinForSetup by remember { mutableStateOf("") }
    var setupStage by remember { mutableStateOf(1) }
    var errorMessage by remember { mutableStateOf("") }

    val titleText = when (mode) {
        "unlock" -> "Enter Passcode"
        "setup" -> if (setupStage == 1) "Create Passcode" else "Confirm Passcode"
        else -> "Enter Passcode"
    }

    val subtitleText = when (mode) {
        "unlock" -> "Enter your 4-digit passcode to unlock BlueMesh"
        "setup" -> if (setupStage == 1) "Enter a 4-digit security PIN" else "Re-enter your new PIN to confirm"
        else -> ""
    }

    val onDigitClick: (String) -> Unit = { digit ->
        if (pinInput.length < 4) {
            pinInput += digit
            errorMessage = ""
        }
    }

    val onBackspaceClick: () -> Unit = {
        if (pinInput.isNotEmpty()) {
            pinInput = pinInput.dropLast(1)
            errorMessage = ""
        }
    }

    val onSubmitClick: () -> Unit = {
        if (pinInput.length == 4) {
            when (mode) {
                "unlock", "verify", "verify_change" -> {
                    if (repository.verifyPasscode(pinInput)) {
                        onSuccess()
                    } else {
                        errorMessage = "Incorrect Passcode"
                        pinInput = ""
                    }
                }
                "setup" -> {
                    if (setupStage == 1) {
                        tempPinForSetup = pinInput
                        pinInput = ""
                        setupStage = 2
                    } else {
                        if (pinInput == tempPinForSetup) {
                            repository.savePasscode(pinInput)
                            Toast.makeText(context, "Passcode Enabled Successfully", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        } else {
                            errorMessage = "Passcodes do not match. Try again."
                            pinInput = ""
                            tempPinForSetup = ""
                            setupStage = 1
                        }
                    }
                }
            }
        } else {
            errorMessage = "Passcode must be exactly 4 digits"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E))
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .padding(24.dp)
    ) {
        if (onBack != null && mode != "unlock") {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Lock Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6))
                            )
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Icon",
                        modifier = Modifier.size(36.dp),
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = titleText,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = subtitleText,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            // PIN Dots Indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until 4) {
                        val active = i < pinInput.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (active) Color(0xFF3B82F6) else Color.Transparent)
                                .border(
                                    width = 2.dp,
                                    color = if (active) Color(0xFF3B82F6) else Color(0xFF475569),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedVisibility(
                    visible = errorMessage.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = Color(0xFFEF4444),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Keypad
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val numRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9")
                )

                for (row in numRows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (digit in row) {
                            Box(
                                modifier = Modifier.weight(1f)
                            ) {
                                KeypadButton(
                                    text = digit,
                                    onClick = { onDigitClick(digit) }
                                )
                            }
                        }
                    }
                }

                // Last Row: Backspace, 0, Submit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Backspace
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = onBackspaceClick,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1D263B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Backspace,
                                contentDescription = "Backspace",
                                tint = Color.White
                            )
                        }
                    }

                    // 0
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        KeypadButton(
                            text = "0",
                            onClick = { onDigitClick("0") }
                        )
                    }

                    // Submit
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = onSubmitClick,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF10B981), Color(0xFF059669))
                                    )
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "OK",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeypadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.0f)
            .clip(CircleShape)
            .background(Color(0xFF1D263B))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color.White),
                onClick = onClick
            )
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
