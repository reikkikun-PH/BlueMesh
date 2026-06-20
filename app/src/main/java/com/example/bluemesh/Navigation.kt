package com.example.bluemesh

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.ui.chat.ChatScreen
import com.example.bluemesh.ui.contacts.ContactsScreen
import com.example.bluemesh.ui.lock.LockScreen
import com.example.bluemesh.ui.main.MainScreen
import com.example.bluemesh.ui.security.SecurityScreen
import com.example.bluemesh.ui.setup.SetupScreen

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val repository = remember { DefaultDataRepository.getInstance(context) }
    val prefs = remember { context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE) }
    val initialName = remember { prefs.getString("display_name", "") ?: "" }
    val isVolunteersEdition = false // Set to true in Volunteers Edition copy
    val passcodeEnabled = repository.isPasscodeEnabled()

    val startDestination = if (initialName.isEmpty()) Setup 
                           else if (!passcodeEnabled) SetupPasscode 
                           else Main
    val backStack = rememberNavBackStack(startDestination)

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
