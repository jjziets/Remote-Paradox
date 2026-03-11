package com.remoteparadox.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteparadox.app.data.AlarmStatus
import com.remoteparadox.app.data.PartitionInfo
import com.remoteparadox.app.data.ZoneEvent
import com.remoteparadox.app.data.ZoneInfo
import com.remoteparadox.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    alarmStatus: AlarmStatus?,
    zoneHistory: List<ZoneEvent>,
    selectedPartition: Int,
    isLoading: Boolean,
    actionInProgress: String?,
    error: String?,
    username: String?,
    onSelectPartition: (Int) -> Unit,
    onArmAway: (code: String, partitionId: Int) -> Unit,
    onArmStay: (code: String, partitionId: Int) -> Unit,
    onDisarm: (code: String, partitionId: Int) -> Unit,
    onBypass: (zoneId: Int, bypass: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    var showCodeDialog by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    val partitions = alarmStatus?.partitions.orEmpty()
    val currentPartition = partitions.find { it.id == selectedPartition } ?: partitions.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Paradox", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Color.White,
                ),
                actions = {
                    if (username != null) {
                        Text(username, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(end = 4.dp))
                    }
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(Icons.Default.History, "History", tint = if (showHistory) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, "Logout", tint = Color.White)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                ConnectionBanner(alarmStatus?.connected ?: false, isLoading)
            }

            // Partition tabs
            if (partitions.size > 1) {
                item {
                    PartitionTabs(
                        partitions = partitions,
                        selectedId = currentPartition?.id ?: 1,
                        onSelect = onSelectPartition,
                    )
                }
            }

            // Partition arm state card
            item {
                PartitionStateCard(currentPartition)
            }

            // Error
            if (error != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // Control buttons
            item {
                ControlButtons(
                    armed = currentPartition?.armed ?: false,
                    connected = alarmStatus?.connected ?: false,
                    actionInProgress = actionInProgress,
                    onArmAway = { showCodeDialog = "arm_away" },
                    onArmStay = { showCodeDialog = "arm_stay" },
                    onDisarm = { showCodeDialog = "disarm" },
                )
            }

            // Zone list
            val zones = currentPartition?.zones.orEmpty()
            if (zones.isNotEmpty()) {
                item {
                    Text(
                        "${currentPartition?.name ?: "Zones"} — ${zones.size} zones",
                        fontWeight = FontWeight.Bold,
                        color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(zones) { zone ->
                    ZoneRow(zone, onBypass = { onBypass(zone.id, !zone.bypassed) })
                }
            }

            // History section
            if (showHistory && zoneHistory.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Zone History",
                        fontWeight = FontWeight.Bold,
                        color = Color.White, fontSize = 16.sp,
                    )
                }
                items(zoneHistory.take(20)) { event ->
                    HistoryRow(event)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showCodeDialog != null) {
        AlarmCodeDialog(
            action = showCodeDialog!!,
            onConfirm = { code ->
                val pid = currentPartition?.id ?: 1
                when (showCodeDialog) {
                    "arm_away" -> onArmAway(code, pid)
                    "arm_stay" -> onArmStay(code, pid)
                    "disarm" -> onDisarm(code, pid)
                }
                showCodeDialog = null
            },
            onDismiss = { showCodeDialog = null },
        )
    }
}

@Composable
private fun PartitionTabs(
    partitions: List<PartitionInfo>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        partitions.forEach { p ->
            val isSelected = p.id == selectedId
            val (modeColor, _) = partitionModeStyle(p)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(p.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(modeColor))
                        Text(p.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun partitionModeStyle(p: PartitionInfo): Pair<Color, String> {
    return when (p.mode) {
        "away" -> AlarmArmed to "ARMED AWAY"
        "stay" -> AlarmStay to "ARMED STAY"
        "disarmed" -> AlarmDisarmed to "DISARMED"
        else -> Color.Gray to "UNKNOWN"
    }
}

@Composable
private fun ConnectionBanner(connected: Boolean, loading: Boolean) {
    val bg = if (connected) AlarmDisarmed.copy(alpha = 0.15f) else Color(0xFF442222)
    val fg = if (connected) AlarmDisarmed else MaterialTheme.colorScheme.error
    val icon = if (connected) Icons.Default.WifiTethering else Icons.Default.WifiOff
    val text = when {
        loading -> "Connecting..."
        connected -> "Panel connected"
        else -> "Panel offline"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
        } else {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PartitionStateCard(partition: PartitionInfo?) {
    val mode = partition?.mode ?: "unknown"
    val (color, label, icon) = when (mode) {
        "away" -> Triple(AlarmArmed, "ARMED AWAY", Icons.Default.Shield)
        "stay" -> Triple(AlarmStay, "ARMED STAY", Icons.Default.Home)
        "disarmed" -> Triple(AlarmDisarmed, "DISARMED", Icons.Default.LockOpen)
        else -> Triple(Color.Gray, "UNKNOWN", Icons.Default.HelpOutline)
    }
    val zones = partition?.zones.orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
            }
            Column {
                Text(label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
                Text(
                    "${partition?.name ?: "—"}  •  ${zones.size} zones  •  ${zones.count { it.open }} open",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                val bypassed = zones.count { it.bypassed }
                if (bypassed > 0) {
                    Text(
                        "$bypassed bypassed",
                        fontSize = 12.sp,
                        color = AlarmStay.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(
    armed: Boolean,
    connected: Boolean,
    actionInProgress: String?,
    onArmAway: () -> Unit,
    onArmStay: () -> Unit,
    onDisarm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onArmAway,
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = connected && actionInProgress == null,
                colors = ButtonDefaults.buttonColors(containerColor = AlarmArmed),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (actionInProgress == "arm_away") {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.Shield, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ARM AWAY", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Button(
                onClick = onArmStay,
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = connected && actionInProgress == null,
                colors = ButtonDefaults.buttonColors(containerColor = AlarmStay),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (actionInProgress == "arm_stay") {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ARM STAY", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Button(
            onClick = onDisarm,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = connected && actionInProgress == null,
            colors = ButtonDefaults.buttonColors(containerColor = AlarmDisarmed),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (actionInProgress == "disarm") {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Default.LockOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("DISARM", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: ZoneInfo, onBypass: () -> Unit) {
    val (statusColor, statusText) = if (zone.open) ZoneOpen to "Open" else ZoneClosed to "Closed"
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(if (zone.bypassed) Color.Gray else statusColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(zone.name, color = Color.White, fontSize = 14.sp)
                if (zone.bypassed) {
                    Text("BYPASSED", color = AlarmStay, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (zone.open) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Warning, null, tint = ZoneOpen, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onBypass, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (zone.bypassed) Icons.Default.RemoveCircleOutline else Icons.Default.DoNotDisturb,
                    contentDescription = if (zone.bypassed) "Un-bypass" else "Bypass",
                    tint = if (zone.bypassed) AlarmStay else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(event: ZoneEvent) {
    val color = if (event.event == "opened") ZoneOpen else ZoneClosed
    val ts = event.timestamp.take(19).replace("T", " ")
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(event.zoneName, color = Color.White, fontSize = 13.sp)
                Text(ts, color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
            }
            Text(
                event.event.uppercase(),
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AlarmCodeDialog(
    action: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val title = when (action) {
        "arm_away" -> "Arm Away"
        "arm_stay" -> "Arm Stay"
        "disarm" -> "Disarm"
        else -> action
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Alarm code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code) },
                enabled = code.length >= 4,
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
