package com.example.bluemesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.theme.BlueMeshTheme
import com.example.bluemesh.ui.lock.LockScreen

class MainActivity : ComponentActivity() {
  private val hasPermissionsState = mutableStateOf(false)
  private val isUnlocked = mutableStateOf(false)
  private var lastBackgroundTime: Long = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    )
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      isUnlocked.value = savedInstanceState.getBoolean("is_unlocked", false)
      lastBackgroundTime = savedInstanceState.getLong("last_background_time", 0L)
    }
    setContent {
      BlueMeshTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val context = LocalContext.current
          val repository = remember { DefaultDataRepository.getInstance(context.applicationContext) }
          val passcodeEnabled = repository.isPasscodeEnabled()

          if (passcodeEnabled && !isUnlocked.value) {
            LockScreen(
              mode = "unlock",
              onSuccess = {
                isUnlocked.value = true
              },
              onBack = {
                finish()
              }
            )
          } else if (hasPermissionsState.value) {
            MainNavigation()
          } else {
            PermissionScreen(
              onGrantClick = {
                requestPermissionsLauncher.launch(getRequiredPermissions())
              },
              onSettingsClick = {
                openAppSettings()
              }
            )
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    val repository = DefaultDataRepository.getInstance(applicationContext)
    if (repository.isPasscodeEnabled()) {
      val elapsed = System.currentTimeMillis() - lastBackgroundTime
      if (lastBackgroundTime > 0L && elapsed > 300000L) { // 5 minutes (300,000 ms)
        isUnlocked.value = false
      }
    }
  }

  override fun onStop() {
    super.onStop()
    lastBackgroundTime = System.currentTimeMillis()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean("is_unlocked", isUnlocked.value)
    outState.putLong("last_background_time", lastBackgroundTime)
  }

  override fun onResume() {
    super.onResume()
    hasPermissionsState.value = hasPermissions(this)
  }

  private val requestPermissionsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissionsMap ->
    val allGranted = permissionsMap.values.all { it }
    if (allGranted) {
      hasPermissionsState.value = true
    }
  }

  private fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
      )
    } else {
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
    }
  }

  private fun hasPermissions(context: Context): Boolean {
    return getRequiredPermissions().all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
  }

  private fun openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", packageName, null)
    }
    startActivity(intent)
  }
}

@Composable
fun PermissionScreen(
  onGrantClick: () -> Unit,
  onSettingsClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0E131E)) // Matching v20 Navy palette
      .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      // Animated pulsing Bluetooth Halo
      val infiniteTransition = rememberInfiniteTransition(label = "pulse")
      val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
          animation = tween(1500, easing = LinearEasing),
          repeatMode = RepeatMode.Restart
        ),
        label = "scale"
      )
      val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
          animation = tween(1500, easing = LinearEasing),
          repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
      )

      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(140.dp)
      ) {
        // Pulsing outer halo
        Box(
          modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                colors = listOf(
                  Color(0xFF3B82F6).copy(alpha = alpha),
                  Color(0xFF8B5CF6).copy(alpha = 0f)
                )
              )
            )
        )
        // Middle glow ring
        Box(
          modifier = Modifier
            .size(90.dp)
            .clip(CircleShape)
            .border(
              2.dp,
              Brush.linearGradient(
                colors = listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6))
              ),
              CircleShape
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = "Bluetooth Status",
            tint = Color.White,
            modifier = Modifier.size(48.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      Text(
        text = "Bluetooth Access Required",
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = "BlueMesh uses direct peer-to-peer Bluetooth connections to let you discover, connect, and chat with nearby devices offline.",
        color = Color(0xFF94A3B8), // Slate 400
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        lineHeight = 22.sp,
        modifier = Modifier.padding(horizontal = 16.dp)
      )

      Spacer(modifier = Modifier.height(48.dp))

      // Beautiful Gradient Primary Button
      Button(
        onClick = onGrantClick,
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
            text = "Grant Permissions",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Subtle Secondary Button for Settings
      TextButton(
        onClick = onSettingsClick,
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
      ) {
        Text(
          text = "App Settings",
          color = Color(0xFF3B82F6),
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}
