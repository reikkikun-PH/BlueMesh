package com.example.bluemesh.ui.main

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation3.runtime.NavKey
import com.example.bluemesh.Chat
import com.example.bluemesh.ContactsList
import com.example.bluemesh.SecuritySettings
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.data.models.BluetoothPeer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    onEditProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { DefaultDataRepository.getInstance(context.applicationContext) }
    
    val viewModel: MainScreenViewModel = viewModel {
        MainScreenViewModel(repository)
    }
    val peers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val isPasscodeEnabled = remember { repository.isPasscodeEnabled() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    var wasDiscoverableBeforeStop by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.startScanning()
                    if (wasDiscoverableBeforeStop) {
                        viewModel.toggleDiscoverability(true)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    wasDiscoverableBeforeStop = isAdvertising
                    viewModel.stopScanning()
                    viewModel.toggleDiscoverability(false)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopScanning()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF1D263B)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "BlueMesh Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(8.dp))
                
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = "Contacts",
                            tint = Color(0xFF3B82F6)
                        )
                    },
                    label = { Text("Contacts", color = Color.White) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick(ContactsList)
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Security",
                            tint = Color(0xFF0284C7)
                        )
                    },
                    label = { Text("Security", color = Color.White) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick(SecuritySettings)
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About Us",
                            tint = Color(0xFF8B5CF6)
                        )
                    },
                    label = { Text("About Us", color = Color.White) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        Toast.makeText(context, "BlueMesh v26.4 — Offline Bluetooth Chat", Toast.LENGTH_SHORT).show()
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E131E))
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Top header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            scope.launch { drawerState.open() }
                        },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                    }

                    Text(
                        text = "BlueMesh",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        style = TextStyle(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF8B5CF6), Color(0xFF3B82F6))
                            )
                        )
                    )

                    IconButton(
                        onClick = { viewModel.startScanning() },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF3B82F6))
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Profile Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1D263B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Chat Profile",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = viewModel.displayName,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onEditProfileClick,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF8B5CF6))
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Profile Name")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Discoverable Switch Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1D263B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Make Discoverable",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isAdvertising) "Broadcasting display name offline" else "Invisible to nearby peers",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }

                        Switch(
                            checked = isAdvertising,
                            onCheckedChange = { viewModel.toggleDiscoverability(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF3B82F6),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Nearby section header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nearby Peers",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    if (isScanning) {
                        CircularProgressIndicator(
                            color = Color(0xFF3B82F6),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (peers.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Searching for nearby BlueMesh users...\nEnsure Bluetooth is active on both devices.",
                            color = Color(0xFF64748B),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(peers, key = { it.uuid.ifEmpty { it.address } }) { peer ->
                            val isContact = remember(peer.uuid) { repository.isContact(peer.uuid) }
                            var isContactState by remember { mutableStateOf(isContact) }
                            
                            PeerItem(
                                peer = peer,
                                isPasscodeEnabled = isPasscodeEnabled,
                                isContact = isContactState,
                                onSaveClick = {
                                    repository.saveContact(peer.uuid, peer.name)
                                    isContactState = true
                                },
                                onClick = {
                                    onItemClick(Chat(peer.uuid, peer.name))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeerItem(
    peer: BluetoothPeer,
    isPasscodeEnabled: Boolean,
    isContact: Boolean,
    onSaveClick: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D263B)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (peer.isOfficial) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Official Profile",
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = peer.uuid.ifEmpty { peer.address },
                    color = Color(0xFF64748B),
                    fontSize = 13.sp
                )
                if (peer.rssi != -100 && peer.allowTracking) {
                    val dist = peer.estimatedDistance
                    val distFormatted = if (dist < 1.0) "Within 1m" else "Est. " + ((dist * 10).toInt() / 10.0) + "m"
                    Text(
                        text = "$distFormatted (Signal: ${peer.rssi} dBm)",
                        color = Color(0xFF3B82F6),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPasscodeEnabled && peer.hasPasscode && !isContact) {
                    IconButton(
                        onClick = onSaveClick,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF10B981))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Save Contact",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = "Connect",
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
