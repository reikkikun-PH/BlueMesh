package com.example.bluemesh

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Setup : NavKey
@Serializable data object Main : NavKey
@Serializable data class Chat(val peerUuid: String, val peerName: String) : NavKey
@Serializable data object ContactsList : NavKey
@Serializable data object SecuritySettings : NavKey
@Serializable data class Lock(val mode: String) : NavKey
@Serializable data object SetupPasscode : NavKey
