package com.remoteparadox.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteparadox.app.BuildConfig
import com.remoteparadox.app.UpdateState
import com.remoteparadox.app.WatchUpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    username: String?,
    serverHost: String?,
    serverPort: Int,
    soundEnabled: Boolean,
    notificationsEnabled: Boolean,
    updateState: UpdateState,
    isAdmin: Boolean = false,
    piUpdate: com.remoteparadox.app.PiUpdateState = com.remoteparadox.app.PiUpdateState(),
    piSystem: com.remoteparadox.app.PiSystemState = com.remoteparadox.app.PiSystemState(),
    onSoundToggle: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onManageUsers: () -> Unit = {},
    onCheckPiUpdate: () -> Unit = {},
    onApplyPiUpdate: () -> Unit = {},
    onRefreshPiSystem: () -> Unit = {},
    onRebootPi: () -> Unit = {},
    onBleLinkPi: () -> Unit = {},
    watchSyncState: com.remoteparadox.app.WatchSyncState = com.remoteparadox.app.WatchSyncState(),
    onSendToWatch: () -> Unit = {},
    watchUpdateState: WatchUpdateState = WatchUpdateState(),
    onCheckWatchUpdate: () -> Unit = {},
    onDownloadWatchUpdate: () -> Unit = {},
    onLogout: () -> Unit,
    onSwitchServer: () -> Unit,
    onBack: () -> Unit,
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showSwitchConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Color.White,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val useTwoColumns = maxWidth >= 600.dp

            if (useTwoColumns) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsAccountCard(username, serverHost, serverPort)
                        if (isAdmin) {
                            SettingsAdminCard(onManageUsers)
                            SettingsPiSystemCard(piSystem, onRefreshPiSystem)
                            SettingsMaintenanceCard(piSystem, onRebootPi)
                        }
                        SettingsConnectivityCard(onBleLinkPi, onSendToWatch, watchSyncState)
                        Spacer(Modifier.height(16.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsAlertsCard(soundEnabled, onSoundToggle, notificationsEnabled, onNotificationsToggle)
                        SettingsWatchAppCard(watchUpdateState, onCheckWatchUpdate, onDownloadWatchUpdate)
                        SettingsAboutCard(updateState, onCheckUpdate, onDownloadUpdate)
                        if (isAdmin) {
                            SettingsPiSoftwareCard(piUpdate, onCheckPiUpdate, onApplyPiUpdate)
                        }
                        SettingsSessionButtons(
                            onLogout = { showLogoutConfirm = true },
                            onSwitchServer = { showSwitchConfirm = true },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Account
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Account", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(username ?: "—", color = Color.White, fontSize = 15.sp)
                            Text("${serverHost ?: "—"}:$serverPort", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
            }

            // Admin section
            if (isAdmin) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Administration", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onManageUsers,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                        ) {
                            Icon(Icons.Default.Group, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Manage Users & Invites", fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Pi System Resources
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pi System", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = onRefreshPiSystem, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, "Refresh", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                            }
                        }

                        if (piSystem.loading) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(12.dp))
                                Text("Loading...", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                            }
                        }

                        val res = piSystem.resources
                        if (res != null) {
                            Spacer(Modifier.height(8.dp))
                            ResourceBar("CPU", res.cpuPercent.toFloat(), "${String.format("%.1f", res.cpuPercent)}%")
                            ResourceBar("Memory", res.memoryPercent.toFloat(), "${res.memoryUsedMb} / ${res.memoryTotalMb} MB")
                            ResourceBar("Storage", res.diskPercent.toFloat(), "${String.format("%.1f", res.diskUsedGb)} / ${String.format("%.1f", res.diskTotalGb)} GB")
                            if (res.uptimeSeconds > 0) {
                                val d = res.uptimeSeconds / 86400
                                val h = (res.uptimeSeconds % 86400) / 3600
                                val m = (res.uptimeSeconds % 3600) / 60
                                val uptimeStr = if (d > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${h}h ${m}m" else "${m}m"
                                Text("Uptime: $uptimeStr", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                        }

                        val wifi = piSystem.wifi
                        if (wifi != null) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wifi, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(wifi.ssid.ifBlank { "Not connected" }, color = Color.White, fontSize = 14.sp)
                                    Text(wifi.ipAddress.ifBlank { "—" }, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                                if (wifi.signalPercent != null) {
                                    Text("${wifi.signalPercent}%", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        val ble = piSystem.bleClients
                        if (ble != null) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            Spacer(Modifier.height(8.dp))
                            Text("BLE Clients (${ble.count})", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            if (ble.clients.isEmpty()) {
                                Text("No BLE clients connected", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                            } else {
                                ble.clients.forEach { client ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    ) {
                                        Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF64B5F6), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            client.name.ifBlank { client.address },
                                            color = Color.White, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            client.username ?: "Unknown",
                                            color = if (client.username != null) Color(0xFF4CAF50) else Color(0xFFE94560),
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }

                        if (piSystem.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(piSystem.error, color = Color(0xFFFF9800), fontSize = 12.sp)
                        }
                    }
                }

                // Reboot Pi (admin only)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Maintenance", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        var showRebootConfirm by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { showRebootConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE94560)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE94560).copy(alpha = 0.5f)),
                            enabled = !piSystem.rebooting,
                        ) {
                            if (piSystem.rebooting) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFE94560))
                            } else {
                                Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Reboot Pi", fontWeight = FontWeight.Medium)
                        }
                        if (showRebootConfirm) {
                            AlertDialog(
                                onDismissRequest = { showRebootConfirm = false },
                                title = { Text("Reboot Pi?") },
                                text = { Text("The alarm controller will be offline for ~60 seconds.") },
                                confirmButton = {
                                    TextButton(onClick = { showRebootConfirm = false; onRebootPi() }) {
                                        Text("Reboot", color = Color(0xFFE94560))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRebootConfirm = false }) { Text("Cancel") }
                                },
                            )
                        }
                    }
                }
            }

            // BLE Link (available to all users)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connectivity", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBleLinkPi,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.5f)),
                    ) {
                        Icon(Icons.Default.Bluetooth, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("BLE Link to Pi", fontWeight = FontWeight.Medium)
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onSendToWatch,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.5f)),
                        enabled = !watchSyncState.syncing,
                    ) {
                        if (watchSyncState.syncing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFFF9800))
                        } else {
                            Icon(Icons.Default.Watch, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Send to Watch", fontWeight = FontWeight.Medium)
                    }

                    if (watchSyncState.message != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            watchSyncState.message,
                            color = if (watchSyncState.isError) Color(0xFFE94560) else Color(0xFF4CAF50),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // Watch App Update
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Watch App", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    when {
                        watchUpdateState.sending -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFFF9800))
                                Spacer(Modifier.width(12.dp))
                                Text("Sending APK to watch...", color = Color.White, fontSize = 14.sp)
                            }
                        }
                        watchUpdateState.downloading -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFFFF9800))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Downloading watch update...", color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { watchUpdateState.downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFFFF9800),
                                    )
                                    Text("${(watchUpdateState.downloadProgress * 100).toInt()}%",
                                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                            }
                        }
                        watchUpdateState.checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking watch version...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }
                        watchUpdateState.success -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Update sent! Confirm install on watch.", color = Color(0xFF4CAF50), fontSize = 14.sp)
                            }
                        }
                        watchUpdateState.updateAvailable && watchUpdateState.latestVersion != null -> {
                            if (watchUpdateState.watchVersion != null) {
                                Text("Current: v${watchUpdateState.watchVersion}",
                                    color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                            }
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.15f)),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Update available: v${watchUpdateState.latestVersion}",
                                        color = Color(0xFFFF9800), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = onDownloadWatchUpdate,
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = watchUpdateState.downloadUrl != null,
                                    ) {
                                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Update Watch", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        watchUpdateState.watchVersion != null && !watchUpdateState.updateAvailable -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Watch is up to date (v${watchUpdateState.watchVersion})", color = Color(0xFF4CAF50), fontSize = 14.sp)
                            }
                        }
                    }

                    if (watchUpdateState.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(watchUpdateState.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    if (!watchUpdateState.downloading && !watchUpdateState.sending) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCheckWatchUpdate,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !watchUpdateState.checking,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.3f)),
                        ) {
                            Icon(Icons.Default.Watch, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Check Watch for Updates", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Alerts
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("Alerts", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))

                    SettingsToggle(
                        icon = Icons.Default.VolumeUp,
                        label = "Alarm sounds",
                        description = "Play sound on alarm trigger, arm, and disarm",
                        checked = soundEnabled,
                        onCheckedChange = onSoundToggle,
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsToggle(
                        icon = Icons.Default.Notifications,
                        label = "Push notifications",
                        description = "Show notification on alarm events",
                        checked = notificationsEnabled,
                        onCheckedChange = onNotificationsToggle,
                    )
                }
            }

            // About & Update
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Remote Paradox", color = Color.White, fontSize = 15.sp)
                            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(Modifier.height(12.dp))

                    when {
                        updateState.downloading -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF4CAF50))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Downloading update...", color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { updateState.downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF4CAF50),
                                    )
                                    Text("${(updateState.downloadProgress * 100).toInt()}%",
                                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                            }
                        }
                        updateState.checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking for updates...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }
                        updateState.updateAvailable && updateState.latestVersion != null -> {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Update available: v${updateState.latestVersion}",
                                        color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    if (!updateState.releaseNotes.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(updateState.releaseNotes.take(200),
                                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                                            maxLines = 4)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = onDownloadUpdate,
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = updateState.downloadUrl != null,
                                    ) {
                                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Download & Install", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        updateState.latestVersion != null && !updateState.updateAvailable -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("You're up to date", color = Color(0xFF4CAF50), fontSize = 14.sp)
                            }
                        }
                    }

                    if (updateState.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(updateState.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    if (!updateState.downloading) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCheckUpdate,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !updateState.checking,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Check for updates", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Pi Software Update (admin only)
            if (isAdmin) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Pi Software", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeveloperBoard, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Remote Paradox Pi", color = Color.White, fontSize = 14.sp)
                                Text("Version ${piUpdate.currentVersion}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                            }
                        }

                        if (piUpdate.message != null) {
                            Spacer(Modifier.height(8.dp))
                            val msgColor = when {
                                piUpdate.pending -> Color(0xFFFF9800)
                                piUpdate.message.contains("up to date", ignoreCase = true) -> Color(0xFF4CAF50)
                                piUpdate.message.startsWith("Can't") || piUpdate.message.startsWith("No connection") || piUpdate.message.startsWith("Connection error") || piUpdate.message.startsWith("Failed") -> Color.White.copy(alpha = 0.5f)
                                else -> Color(0xFF4CAF50)
                            }
                            Text(piUpdate.message, color = msgColor, fontSize = 13.sp)
                        }

                        if (piUpdate.pending && piUpdate.newVersion != null) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = onApplyPiUpdate,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !piUpdate.applying,
                            ) {
                                if (piUpdate.applying) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Apply v${piUpdate.newVersion}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        if (!piUpdate.checking && !piUpdate.applying) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onCheckPiUpdate,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            ) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Check Pi for updates", fontSize = 13.sp)
                            }
                        }

                        if (piUpdate.checking) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log out", fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
                onClick = { showSwitchConfirm = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
            ) {
                Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Switch server", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
        }
            } // end else (single column)
        } // end BoxWithConstraints
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out?") },
            text = { Text("You will need to log in again.") },
            confirmButton = {
                TextButton(onClick = { showLogoutConfirm = false; onLogout() }) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = false },
            title = { Text("Switch server?") },
            text = { Text("This will clear all saved data and return to the QR scan screen.") },
            confirmButton = {
                TextButton(onClick = { showSwitchConfirm = false; onSwitchServer() }) {
                    Text("Switch", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ResourceBar(label: String, percent: Float, detail: String) {
    val clampedPct = percent.coerceIn(0f, 100f)
    val color = when {
        clampedPct < 60f -> Color(0xFF4CAF50)
        clampedPct < 85f -> Color(0xFFFF9800)
        else -> Color(0xFFE94560)
    }
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(detail, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { clampedPct / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            Text(description, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Extracted card composables for two-column layout ──

@Composable
private fun SettingsAccountCard(username: String?, serverHost: String?, serverPort: Int) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Account", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(username ?: "—", color = Color.White, fontSize = 15.sp)
                    Text("${serverHost ?: "—"}:$serverPort", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsAdminCard(onManageUsers: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Administration", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onManageUsers,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Default.Group, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Manage Users & Invites", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SettingsPiSystemCard(
    piSystem: com.remoteparadox.app.PiSystemState,
    onRefreshPiSystem: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pi System", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefreshPiSystem, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
            if (piSystem.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading...", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
            val res = piSystem.resources
            if (res != null) {
                Spacer(Modifier.height(8.dp))
                ResourceBar("CPU", res.cpuPercent.toFloat(), "${String.format("%.1f", res.cpuPercent)}%")
                ResourceBar("Memory", res.memoryPercent.toFloat(), "${res.memoryUsedMb} / ${res.memoryTotalMb} MB")
                ResourceBar("Storage", res.diskPercent.toFloat(), "${String.format("%.1f", res.diskUsedGb)} / ${String.format("%.1f", res.diskTotalGb)} GB")
                if (res.uptimeSeconds > 0) {
                    val d = res.uptimeSeconds / 86400
                    val h = (res.uptimeSeconds % 86400) / 3600
                    val m = (res.uptimeSeconds % 3600) / 60
                    val uptimeStr = if (d > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${h}h ${m}m" else "${m}m"
                    Text("Uptime: $uptimeStr", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            val wifi = piSystem.wifi
            if (wifi != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(wifi.ssid.ifBlank { "Not connected" }, color = Color.White, fontSize = 14.sp)
                        Text(wifi.ipAddress.ifBlank { "—" }, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                    if (wifi.signalPercent != null) {
                        Text("${wifi.signalPercent}%", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            val ble = piSystem.bleClients
            if (ble != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))
                Text("BLE Clients (${ble.count})", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (ble.clients.isEmpty()) {
                    Text("No BLE clients connected", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                } else {
                    ble.clients.forEach { client ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF64B5F6), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(client.name.ifBlank { client.address }, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(client.username ?: "Unknown",
                                color = if (client.username != null) Color(0xFF4CAF50) else Color(0xFFE94560), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (piSystem.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(piSystem.error, color = Color(0xFFFF9800), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsMaintenanceCard(piSystem: com.remoteparadox.app.PiSystemState, onRebootPi: () -> Unit) {
    var showRebootConfirm by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Maintenance", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRebootConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE94560)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE94560).copy(alpha = 0.5f)),
                enabled = !piSystem.rebooting,
            ) {
                if (piSystem.rebooting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFE94560))
                } else {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Reboot Pi", fontWeight = FontWeight.Medium)
            }
            if (showRebootConfirm) {
                AlertDialog(
                    onDismissRequest = { showRebootConfirm = false },
                    title = { Text("Reboot Pi?") },
                    text = { Text("The alarm controller will be offline for ~60 seconds.") },
                    confirmButton = {
                        TextButton(onClick = { showRebootConfirm = false; onRebootPi() }) {
                            Text("Reboot", color = Color(0xFFE94560))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRebootConfirm = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsConnectivityCard(
    onBleLinkPi: () -> Unit,
    onSendToWatch: () -> Unit,
    watchSyncState: com.remoteparadox.app.WatchSyncState,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Connectivity", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBleLinkPi,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Default.Bluetooth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("BLE Link to Pi", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSendToWatch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.5f)),
                enabled = !watchSyncState.syncing,
            ) {
                if (watchSyncState.syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFFF9800))
                } else {
                    Icon(Icons.Default.Watch, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Send to Watch", fontWeight = FontWeight.Medium)
            }
            if (watchSyncState.message != null) {
                Spacer(Modifier.height(4.dp))
                Text(watchSyncState.message, color = if (watchSyncState.isError) Color(0xFFE94560) else Color(0xFF4CAF50), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsAlertsCard(
    soundEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsToggle: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Alerts", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            SettingsToggle(Icons.Default.VolumeUp, "Alarm sounds", "Play sound on alarm trigger, arm, and disarm", soundEnabled, onSoundToggle)
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsToggle(Icons.Default.Notifications, "Push notifications", "Show notification on alarm events", notificationsEnabled, onNotificationsToggle)
        }
    }
}

@Composable
private fun SettingsWatchAppCard(
    watchUpdateState: WatchUpdateState,
    onCheckWatchUpdate: () -> Unit,
    onDownloadWatchUpdate: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Watch App", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            when {
                watchUpdateState.sending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFFF9800))
                        Spacer(Modifier.width(12.dp))
                        Text("Sending APK to watch...", color = Color.White, fontSize = 14.sp)
                    }
                }
                watchUpdateState.downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFFFF9800))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Downloading watch update...", color = Color.White, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { watchUpdateState.downloadProgress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFFF9800))
                            Text("${(watchUpdateState.downloadProgress * 100).toInt()}%", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
                watchUpdateState.checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking watch version...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
                watchUpdateState.success -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Update sent! Confirm install on watch.", color = Color(0xFF4CAF50), fontSize = 14.sp)
                    }
                }
                watchUpdateState.updateAvailable && watchUpdateState.latestVersion != null -> {
                    if (watchUpdateState.watchVersion != null) {
                        Text("Current: v${watchUpdateState.watchVersion}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.15f))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Update available: v${watchUpdateState.latestVersion}", color = Color(0xFFFF9800), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onDownloadWatchUpdate, modifier = Modifier.fillMaxWidth().height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)), shape = RoundedCornerShape(8.dp),
                                enabled = watchUpdateState.downloadUrl != null) {
                                Icon(Icons.Default.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                Text("Update Watch", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                watchUpdateState.watchVersion != null && !watchUpdateState.updateAvailable -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Watch is up to date (v${watchUpdateState.watchVersion})", color = Color(0xFF4CAF50), fontSize = 14.sp)
                    }
                }
            }
            if (watchUpdateState.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(watchUpdateState.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (!watchUpdateState.downloading && !watchUpdateState.sending) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckWatchUpdate, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(8.dp),
                    enabled = !watchUpdateState.checking, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.3f))) {
                    Icon(Icons.Default.Watch, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Check Watch for Updates", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsAboutCard(updateState: UpdateState, onCheckUpdate: () -> Unit, onDownloadUpdate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Remote Paradox", color = Color.White, fontSize = 15.sp)
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))
            when {
                updateState.downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF4CAF50))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Downloading update...", color = Color.White, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { updateState.downloadProgress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF4CAF50))
                            Text("${(updateState.downloadProgress * 100).toInt()}%", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
                updateState.checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking for updates...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
                updateState.updateAvailable && updateState.latestVersion != null -> {
                    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Update available: v${updateState.latestVersion}", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            if (!updateState.releaseNotes.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(updateState.releaseNotes.take(200), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 4)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onDownloadUpdate, modifier = Modifier.fillMaxWidth().height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(8.dp),
                                enabled = updateState.downloadUrl != null) {
                                Icon(Icons.Default.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                                Text("Download & Install", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                updateState.latestVersion != null && !updateState.updateAvailable -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("You're up to date", color = Color(0xFF4CAF50), fontSize = 14.sp)
                    }
                }
            }
            if (updateState.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(updateState.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (!updateState.downloading) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(8.dp),
                    enabled = !updateState.checking, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Check for updates", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsPiSoftwareCard(
    piUpdate: com.remoteparadox.app.PiUpdateState,
    onCheckPiUpdate: () -> Unit,
    onApplyPiUpdate: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Pi Software", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeveloperBoard, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Remote Paradox Pi", color = Color.White, fontSize = 14.sp)
                    Text("Version ${piUpdate.currentVersion}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
            if (piUpdate.message != null) {
                Spacer(Modifier.height(8.dp))
                val msgColor = when {
                    piUpdate.pending -> Color(0xFFFF9800)
                    piUpdate.message.contains("up to date", ignoreCase = true) -> Color(0xFF4CAF50)
                    piUpdate.message.startsWith("Can't") || piUpdate.message.startsWith("No connection") || piUpdate.message.startsWith("Connection error") || piUpdate.message.startsWith("Failed") -> Color.White.copy(alpha = 0.5f)
                    else -> Color(0xFF4CAF50)
                }
                Text(piUpdate.message, color = msgColor, fontSize = 13.sp)
            }
            if (piUpdate.pending && piUpdate.newVersion != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onApplyPiUpdate, modifier = Modifier.fillMaxWidth().height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)), shape = RoundedCornerShape(8.dp),
                    enabled = !piUpdate.applying) {
                    if (piUpdate.applying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apply v${piUpdate.newVersion}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (!piUpdate.checking && !piUpdate.applying) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckPiUpdate, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Check Pi for updates", fontSize = 13.sp)
                }
            }
            if (piUpdate.checking) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSessionButtons(onLogout: () -> Unit, onSwitchServer: () -> Unit) {
    OutlinedButton(
        onClick = onLogout,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
    ) {
        Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Log out", fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onSwitchServer,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE94560)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE94560).copy(alpha = 0.3f)),
    ) {
        Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Switch server", fontWeight = FontWeight.Medium)
    }
}
