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
import com.example.bluemesh.AccessibilitySettings
import com.example.bluemesh.SecuritySettings
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.data.models.BluetoothPeer
import com.example.bluemesh.theme.LocalBlueMeshColors
import com.example.bluemesh.theme.LocalIsDarkMode
import com.example.bluemesh.theme.LocalOnThemeToggle
import com.example.bluemesh.ui.AccessibilityState
import com.example.bluemesh.ui.LocalAccessibility
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    onEditProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accessibility = LocalAccessibility.current
    val repository = remember { DefaultDataRepository.getInstance(context.applicationContext) }
    
    val viewModel: MainScreenViewModel = viewModel {
        MainScreenViewModel(repository)
    }
    val peers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val isPasscodeEnabled = remember { repository.isPasscodeEnabled() }
    var isDiscoverable by remember { mutableStateOf(repository.isDiscoverableEnabled()) }
    val colors = LocalBlueMeshColors.current
    val isDarkMode = LocalIsDarkMode.current
    val onThemeToggle = LocalOnThemeToggle.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isDiscoverable = repository.isDiscoverableEnabled()
                    viewModel.startScanning(clearList = false)
                    if (repository.isDiscoverableEnabled()) {
                        viewModel.toggleDiscoverability(true)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Keep scanning active in paused/background states to allow background auto-connections to function
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "BlueMesh Settings",
                    color = colors.onSurface,
                    fontSize = accessibility.headerFontSize,
                    fontWeight = accessibility.headerFontWeight,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider(color = colors.divider)
                Spacer(modifier = Modifier.height(8.dp))
                
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = "Contacts",
                            tint = colors.primary
                        )
                    },
                    label = { Text("Contacts", color = colors.onSurface) },
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
                    label = { Text("Security", color = colors.onSurface) },
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
                            imageVector = Icons.Default.TextFields,
                            contentDescription = "Accessibility",
                            tint = Color(0xFF8B5CF6)
                        )
                    },
                    label = { Text("Accessibility", color = colors.onSurface) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onItemClick(AccessibilitySettings)
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
                            contentDescription = "About",
                            tint = Color(0xFF8B5CF6)
                        )
                    },
                    label = { Text("About", color = colors.onSurface) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://reikkikun-ph.github.io/BlueMesh-Website/"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.divider)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Dark Theme",
                            color = colors.onSurface,
                            fontSize = accessibility.bodyFontSize,
                            fontWeight = accessibility.bodyFontWeight
                        )
                        Text(
                            text = if (isDarkMode) "Dark mode active" else "Light mode active",
                            color = colors.textSecondary,
                            fontSize = accessibility.captionFontSize
                        )
                    }
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { onThemeToggle?.invoke(it) },
                        colors = SwitchDefaults.colors(
checkedThumbColor = Color.White,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.switchUncheckedThumb,
                    uncheckedTrackColor = colors.switchUncheckedTrack
                        )
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                val versionName = remember {
                    try {
                        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        }
                        packageInfo.versionName ?: "28.22"
                    } catch (e: Exception) {
                        "28.22"
                    }
                }
                Text(
                    text = "BlueMesh Version $versionName",
                    color = colors.textTertiary,
                    fontSize = accessibility.captionFontSize,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
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
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface)
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                    }

                    Text(
                        text = "BlueMesh",
                        fontSize = accessibility.headerFontSize * 1.4f,
                        fontWeight = accessibility.headerFontWeight,
                        style = TextStyle(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF8B5CF6), colors.primary)
                            )
                        )
                    )

                    IconButton(
                        onClick = { viewModel.startScanning(clearList = true) },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Profile Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
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
                                color = colors.textSecondary,
                                fontSize = accessibility.captionFontSize,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = viewModel.displayName,
                                color = colors.onSurface,
                                fontSize = accessibility.bodyFontSize,
                                fontWeight = accessibility.bodyFontWeight
                            )
                        }

                        IconButton(
                            onClick = onEditProfileClick,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.secondary)
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Profile Name")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Discoverable Switch Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
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
                                color = colors.onSurface,
                                fontSize = accessibility.bodyFontSize,
                                fontWeight = accessibility.bodyFontWeight
                            )
                            Text(
                                text = if (isDiscoverable) "Broadcasting display name offline" else "Invisible to nearby peers",
                                color = colors.textSecondary,
                                fontSize = accessibility.captionFontSize
                            )
                        }

                        Switch(
                            checked = isDiscoverable,
                            onCheckedChange = {
                                isDiscoverable = it
                                viewModel.toggleDiscoverability(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.primary,
                                uncheckedThumbColor = colors.textSecondary,
                                uncheckedTrackColor = colors.divider
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
                        color = colors.onSurface,
                        fontSize = accessibility.bodyFontSize * 1.1f,
                        fontWeight = accessibility.headerFontWeight,
                        modifier = Modifier.weight(1f)
                    )

                    if (isScanning) {
                        CircularProgressIndicator(
                            color = colors.primary,
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
                            color = colors.textTertiary,
                            fontSize = accessibility.bodyFontSize,
                            textAlign = TextAlign.Center,
                            lineHeight = accessibility.bodyFontSize * 1.5f
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(peers, key = { it.uuid.ifEmpty { it.address } }) { peer ->
                            val isContact = remember(peer.uuid, peers) { repository.isContact(peer.uuid) }
                            var isContactState by remember(peer.uuid, isContact) { mutableStateOf(isContact) }
                            
                            PeerItem(
                                peer = peer,
                                accessibility = accessibility,
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
}@Composable
fun PeerItem(
    peer: BluetoothPeer,
    accessibility: AccessibilityState = AccessibilityState(),
    isPasscodeEnabled: Boolean,
    isContact: Boolean,
    onSaveClick: () -> Unit,
    onClick: () -> Unit
) {
    val colors = LocalBlueMeshColors.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
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
                        text = peer.name.ifEmpty { "Unknown User" },
                        color = colors.onSurface,
                        fontSize = accessibility.bodyFontSize,
                        fontWeight = accessibility.bodyFontWeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (peer.isOfficial) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Official Profile",
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = peer.uuid.ifEmpty { peer.address },
                    color = colors.textTertiary,
                    fontSize = accessibility.captionFontSize
                )
                if (peer.rssi != -100 && peer.allowTracking) {
                    val dist = peer.estimatedDistance
                    val distFormatted = if (dist < 1.0) "Within 1m" else "Est. " + ((dist * 10).toInt() / 10.0) + "m"
                    Text(
                        text = "$distFormatted (Signal: ${peer.rssi} dBm)",
                        color = colors.primary,
                        fontSize = accessibility.captionFontSize,
                        fontWeight = accessibility.bodyFontWeight
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isContact) {
                    IconButton(
                        onClick = onSaveClick,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.success)
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
                    tint = colors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
