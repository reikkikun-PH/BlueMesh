package com.example.bluemesh

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.ui.AccessibilityState
import com.example.bluemesh.ui.LocalAccessibility
import com.example.bluemesh.ui.accessibility.AccessibilityScreen
import com.example.bluemesh.ui.chat.ChatScreen
import com.example.bluemesh.ui.contacts.ContactsScreen
import com.example.bluemesh.ui.computeAccessibilityState
import com.example.bluemesh.ui.lock.LockScreen
import com.example.bluemesh.ui.main.MainScreen
import com.example.bluemesh.ui.security.SecurityScreen
import com.example.bluemesh.ui.setup.SetupScreen
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Setup : NavKey
@Serializable data object Main : NavKey
@Serializable data class Chat(val peerUuid: String, val peerName: String) : NavKey
@Serializable data object ContactsList : NavKey
@Serializable data object SecuritySettings : NavKey
@Serializable data object AccessibilitySettings : NavKey
@Serializable data class Lock(val mode: String) : NavKey
@Serializable data object SetupPasscode : NavKey

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val repository = remember { DefaultDataRepository.getInstance(context) }
    val prefs = remember { context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE) }
    val initialName = remember { prefs.getString("display_name", "") ?: "" }
    val isVolunteersEdition = false
    val passcodeEnabled = repository.isPasscodeEnabled()

    val startDestination = if (initialName.isEmpty()) Setup 
                           else if (!passcodeEnabled) SetupPasscode 
                           else Main
    val backStack = rememberNavBackStack(startDestination)
    var accessibilityState by remember { mutableStateOf(computeAccessibilityState(repository.isBoldTextEnabled(), repository.getFontSizeLevel())) }

    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "bold_text_enabled" || key == "font_size_level") {
                accessibilityState = computeAccessibilityState(repository.isBoldTextEnabled(), repository.getFontSizeLevel())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    CompositionLocalProvider(LocalAccessibility provides accessibilityState) {
        NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                val last = backStack.lastOrNull()
                if (last == Main && (startDestination == Setup || startDestination == SetupPasscode)) {
                    // Prevent back navigation from Main to Setup/SetupPasscode
                } else if (last == SetupPasscode && startDestination == Setup) {
                    // Prevent back navigation from SetupPasscode to Setup
                } else {
                    backStack.removeLastOrNull()
                }
            }
        },
        entryProvider = entryProvider {
            entry<Setup> {
                SetupScreen(
                    onSetupComplete = {
                        if (!repository.isPasscodeEnabled()) {
                            backStack.add(SetupPasscode)
                        } else {
                            backStack.add(Main)
                        }
                    }
                )
            }
            entry<SetupPasscode> {
                LockScreen(
                    mode = "setup",
                    onSuccess = {
                        backStack.add(Main)
                    },
                    onBack = null
                )
            }
            entry<Main> {
                MainScreen(
                    onItemClick = { navKey -> backStack.add(navKey) },
                    onEditProfileClick = {
                        backStack.add(Setup)
                    }
                )
            }
            entry<Chat> { key ->
                ChatScreen(
                    peerUuid = key.peerUuid,
                    peerName = key.peerName,
                    onBackClick = {
                        backStack.removeLastOrNull()
                    }
                )
            }
            entry<ContactsList> {
                ContactsScreen(
                    onBackClick = {
                        backStack.removeLastOrNull()
                    },
                    onNavigateToChat = { uuid, name ->
                        backStack.add(Chat(uuid, name))
                    },
                    onNavigateToSecurity = {
                        backStack.add(SecuritySettings)
                    }
                )
            }
            entry<SecuritySettings> {
                SecurityScreen(
                    onBackClick = {
                        backStack.removeLastOrNull()
                    },
                    onNavigateToLock = { mode ->
                        backStack.add(Lock(mode))
                    }
                )
            }
            entry<AccessibilitySettings> {
                AccessibilityScreen(
                    onBackClick = {
                        backStack.removeLastOrNull()
                    }
                )
            }
            entry<Lock> { key ->
                LockScreen(
                    mode = key.mode,
                    onSuccess = {
                        when (key.mode) {
                            "setup" -> {
                                backStack.removeLastOrNull()
                            }
                            "verify" -> {
                                repository.disablePasscode()
                                backStack.removeLastOrNull()
                            }
                            "verify_change" -> {
                                backStack.removeLastOrNull()
                                backStack.add(Lock("setup"))
                            }
                            "verify_reset_id" -> {
                                backStack.removeLastOrNull()
                            }
                        }
                    },
                    onBack = {
                        backStack.removeLastOrNull()
                    }
                )
            }
        }
    )
    }
}
