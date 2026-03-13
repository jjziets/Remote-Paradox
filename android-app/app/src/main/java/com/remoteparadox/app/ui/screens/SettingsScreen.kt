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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    username: String?,
    serverHost: String?,
    serverPort: Int,
    soundEnabled: Boolean,
    notificationsEnabled: Boolean,
    updateState: UpdateState,
    onSoundToggle: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
