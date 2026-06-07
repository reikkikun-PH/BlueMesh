package com.example.bitchat_lite

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
import com.example.bitchat_lite.data.DefaultDataRepository
import com.example.bitchat_lite.ui.chat.ChatScreen
import com.example.bitchat_lite.ui.contacts.ContactsScreen
import com.example.bitchat_lite.ui.lock.LockScreen
import com.example.bitchat_lite.ui.main.MainScreen
import com.example.bitchat_lite.ui.security.SecurityScreen
import com.example.bitchat_lite.ui.setup.SetupScreen

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val repository = remember { DefaultDataRepository.getInstance(context) }
    val prefs = remember { context.getSharedPreferences("bluemesh_prefs", Context.MODE_PRIVATE) }
    val initialName = remember { prefs.getString("display_name", "") ?: "" }

    val startDestination = if (initialName.isEmpty()) Setup else Main
    val backStack = rememberNavBackStack(startDestination)

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                val last = backStack.lastOrNull()
                if (last == Main && startDestination == Setup) {
                    // Prevent back navigation from Main to Setup
                } else {
                    backStack.removeLastOrNull()
                }
            }
        },
        entryProvider = entryProvider {
            entry<Setup> {
                SetupScreen(
                    onSetupComplete = {
                        backStack.add(Main)
                    }
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
                LaunchedEffect(key.peerUuid) {
                    repository.setActiveChat(key.peerUuid)
                }
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
