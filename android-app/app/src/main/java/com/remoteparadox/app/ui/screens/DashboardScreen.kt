package com.remoteparadox.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteparadox.app.data.AlarmStatus
import com.remoteparadox.app.data.PanelEvent
import com.remoteparadox.app.data.PartitionInfo
import com.remoteparadox.app.data.ZoneInfo
import com.remoteparadox.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    alarmStatus: AlarmStatus?,
    eventHistory: List<PanelEvent>,
    selectedPartition: Int,
    isLoading: Boolean,
    actionInProgress: String?,
    error: String?,
    username: String?,
    savedAlarmCode: String?,
    onSelectPartition: (Int) -> Unit,
    onArmAway: (code: String, partitionId: Int) -> Unit,
    onArmStay: (code: String, partitionId: Int) -> Unit,
    onDisarm: (code: String, partitionId: Int) -> Unit,
    onBypass: (zoneId: Int, bypass: Boolean) -> Unit,
    onPanic: (panicType: String, partitionId: Int) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    var showCodeDialog by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableIntStateOf(0) } // 0=Zones, 1=History

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

            item { ConnectionBanner(alarmStatus?.connected ?: false, isLoading) }

            // Triggered alarm banner
            if (currentPartition?.mode == "triggered") {
                item { AlarmBanner(currentPartition, onDisarm = { code ->
                    onDisarm(code, currentPartition.id)
                }, savedCode = savedAlarmCode) }
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

            // Partition state card
            item { PartitionStateCard(currentPartition) }

            // Error
            if (error != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }

            // Control buttons
            item {
                val pid = currentPartition?.id ?: 1
                ControlButtons(
                    partition = currentPartition,
                    connected = alarmStatus?.connected ?: false,
                    actionInProgress = actionInProgress,
                    savedCode = savedAlarmCode,
                    onArmAway = { if (savedAlarmCode != null) onArmAway(savedAlarmCode, pid) else showCodeDialog = "arm_away" },
                    onArmStay = { if (savedAlarmCode != null) onArmStay(savedAlarmCode, pid) else showCodeDialog = "arm_stay" },
                    onDisarm = { if (savedAlarmCode != null) onDisarm(savedAlarmCode, pid) else showCodeDialog = "disarm" },
                )
            }

            // Panic buttons
            item {
                val pid = currentPartition?.id ?: 1
                PanicButtons(
                    connected = alarmStatus?.connected ?: false,
                    actionInProgress = actionInProgress,
                    onPanic = { type -> onPanic(type, pid) },
                )
            }

            // View tabs: Zones / History
            item {
                TabRow(
                    selectedTabIndex = currentTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                ) {
                    Tab(selected = currentTab == 0, onClick = { currentTab = 0 },
                        text = { Text("Zones", fontWeight = FontWeight.SemiBold) })
                    Tab(selected = currentTab == 1, onClick = { currentTab = 1 },
                        text = { Text("History", fontWeight = FontWeight.SemiBold) })
                }
            }

            if (currentTab == 0) {
                // Zone grid
                val zones = currentPartition?.zones.orEmpty()
                if (zones.isNotEmpty()) {
                    item {
                        Text(
                            "${currentPartition?.name ?: "Zones"} — ${zones.size} zones",
                            fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    item {
                        ZoneGrid(zones = zones, onBypass = { zoneId, bypass -> onBypass(zoneId, bypass) })
                    }
                }
            } else {
                // History
                if (eventHistory.isNotEmpty()) {
                    items(eventHistory.take(30)) { event ->
                        HistoryRow(event)
                    }
                } else {
                    item {
                        Text("No events yet", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showCodeDialog != null) {
        AlarmCodeDialog(
            action = showCodeDialog!!,
            initialCode = savedAlarmCode.orEmpty(),
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

// ── Alarm triggered banner ──

@Composable
private fun AlarmBanner(partition: PartitionInfo, savedCode: String?, onDisarm: (String) -> Unit) {
    val alarmZones = partition.zones.filter { it.alarm || it.wasInAlarm }
    val names = alarmZones.joinToString(", ") { it.name }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE94560)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("\uD83D\uDEA8 ALARM TRIGGERED", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            if (names.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Zone${if (alarmZones.size > 1) "s" else ""}: $names",
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { savedCode?.let { onDisarm(it) } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(10.dp),
                enabled = savedCode != null,
            ) {
                Text("\uD83D\uDD13 DISARM NOW", color = Color(0xFFE94560), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

// ── Partition tabs ──

@Composable
private fun PartitionTabs(partitions: List<PartitionInfo>, selectedId: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        partitions.forEach { p ->
            val isSelected = p.id == selectedId
            val modeColor = partitionModeColor(p)
            val extra = when {
                p.mode == "triggered" -> " \u26A0"
                p.mode == "arming" -> " \u23F3"
                p.entryDelay -> " \uD83D\uDEAA"
                else -> ""
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(p.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(modeColor))
                        Text("${p.name}$extra", fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun partitionModeColor(p: PartitionInfo): Color = when (p.mode) {
    "armed_away" -> AlarmArmed
    "armed_home" -> AlarmStay
    "arming" -> Color(0xFFFFEB3B)
    "triggered" -> AlarmArmed
    "disarmed" -> AlarmDisarmed
    else -> Color.Gray
}

// ── Connection banner ──

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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
        else Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Partition state card ──

@Composable
private fun PartitionStateCard(partition: PartitionInfo?) {
    val mode = partition?.mode ?: "unknown"
    val (color, label, icon) = when {
        mode == "triggered" -> Triple(AlarmArmed, "TRIGGERED", Icons.Default.Warning)
        partition?.entryDelay == true -> Triple(Color(0xFFFF5722), "ENTRY DELAY", Icons.Default.DoorFront)
        mode == "arming" -> Triple(Color(0xFFFFEB3B), "ARMING...", Icons.Default.HourglassTop)
        mode == "armed_away" -> Triple(AlarmArmed, "ARMED AWAY", Icons.Default.Shield)
        mode == "armed_home" -> Triple(AlarmStay, "ARMED HOME", Icons.Default.Home)
        mode == "disarmed" -> Triple(AlarmDisarmed, "DISARMED", Icons.Default.LockOpen)
        else -> Triple(Color.Gray, "UNKNOWN", Icons.Default.HelpOutline)
    }
    val zones = partition?.zones.orEmpty()
    val alarmCount = zones.count { it.alarm || it.wasInAlarm }
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
                val sub = buildString {
                    append("${partition?.name ?: "—"}  •  ${zones.size} zones  •  ${zones.count { it.open }} open")
                    val bp = zones.count { it.bypassed }
                    if (bp > 0) append("  •  $bp bypassed")
                    if (alarmCount > 0) append("  •  $alarmCount alarm")
                    if (partition?.ready == false && mode == "disarmed") append("  •  NOT READY")
                }
                Text(sub, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

// ── Control buttons ──

@Composable
private fun ControlButtons(
    partition: PartitionInfo?,
    connected: Boolean,
    actionInProgress: String?,
    savedCode: String?,
    onArmAway: () -> Unit,
    onArmStay: () -> Unit,
    onDisarm: () -> Unit,
) {
    val mode = partition?.mode ?: "disarmed"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            mode == "arming" -> {
                Button(
                    onClick = onDisarm,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = connected && actionInProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmDisarmed),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CANCEL ARMING", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            mode == "triggered" || partition?.entryDelay == true -> {
                Button(
                    onClick = onDisarm,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = connected && actionInProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmDisarmed),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (actionInProgress == "disarm") {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.LockOpen, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("DISARM NOW", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
            partition?.armed == true -> {
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
            else -> {
                val ready = partition?.ready ?: true
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onArmAway,
                        modifier = Modifier.weight(1f).height(52.dp),
                        enabled = connected && actionInProgress == null && ready,
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
                        enabled = connected && actionInProgress == null && ready,
                        colors = ButtonDefaults.buttonColors(containerColor = AlarmStay),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (actionInProgress == "arm_stay") {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("ARM HOME", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                if (!ready) {
                    Text("System not ready — close all zones first",
                        fontSize = 12.sp, color = AlarmStay.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 4.dp))
                }
                Button(
                    onClick = onDisarm,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = connected && actionInProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmDisarmed),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.LockOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DISARM", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ── Panic buttons ──

@Composable
private fun PanicButtons(
    connected: Boolean,
    actionInProgress: String?,
    onPanic: (String) -> Unit,
) {
    var showConfirm by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { showConfirm = "emergency" },
            modifier = Modifier.weight(1f).height(46.dp),
            enabled = connected && actionInProgress == null,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE94560)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE94560).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("\uD83D\uDEA8 PANIC", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = { showConfirm = "medical" },
            modifier = Modifier.weight(1f).height(46.dp),
            enabled = connected && actionInProgress == null,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF42A5F5)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF42A5F5).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("\uD83C\uDFE5 MEDICAL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = { showConfirm = "fire" },
            modifier = Modifier.weight(1f).height(46.dp),
            enabled = connected && actionInProgress == null,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7043)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF7043).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("\uD83D\uDD25 FIRE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }

    if (showConfirm != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = null },
            title = { Text("Confirm Panic") },
            text = { Text("Send ${showConfirm!!.uppercase()} alert? This will trigger the alarm immediately.") },
            confirmButton = {
                TextButton(onClick = {
                    onPanic(showConfirm!!)
                    showConfirm = null
                }) { Text("SEND", color = Color(0xFFE94560)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

// ── Zone grid ──

@Composable
private fun ZoneGrid(zones: List<ZoneInfo>, onBypass: (Int, Boolean) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(zones, key = { it.id }) { zone ->
            ZoneCard(zone, onBypass = { onBypass(zone.id, !zone.bypassed) })
        }
    }
}

@Composable
private fun ZoneCard(zone: ZoneInfo, onBypass: () -> Unit) {
    val statusColor = if (zone.open) ZoneOpen else ZoneClosed
    val dotColor = if (zone.bypassed) Color.Gray else statusColor
    val bgColor = when {
        zone.alarm -> Color(0xFFE94560).copy(alpha = 0.2f)
        zone.wasInAlarm -> AlarmStay.copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        zone.alarm -> Color(0xFFE94560)
        zone.wasInAlarm -> AlarmStay.copy(alpha = 0.3f)
        else -> Color.Transparent
    }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (borderColor != Color.Transparent) androidx.compose.foundation.BorderStroke(1.dp, borderColor) else null,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Text(zone.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (zone.bypassed) {
                        Text("BYP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AlarmStay,
                            modifier = Modifier.background(AlarmStay.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    if (zone.alarm) {
                        Text("ALARM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE94560),
                            modifier = Modifier.background(Color(0xFFE94560).copy(alpha = 0.3f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    } else if (zone.wasInAlarm) {
                        Text("WAS ALARM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AlarmStay,
                            modifier = Modifier.background(AlarmStay.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    Text(if (zone.open) "Open" else "Closed", color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = onBypass, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (zone.bypassed) Icons.Default.RemoveCircleOutline else Icons.Default.DoNotDisturb,
                        contentDescription = if (zone.bypassed) "Un-bypass" else "Bypass",
                        tint = if (zone.bypassed) AlarmStay else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ── History row ──

@Composable
private fun HistoryRow(event: PanelEvent) {
    val prop = event.property
    val color = when {
        prop == "alarm" || prop == "panic" || prop == "audible_alarm" -> Color(0xFFE94560)
        prop == "open" && event.value == "true" -> ZoneOpen
        prop == "entry_delay" || prop == "exit_delay" -> Color(0xFFFFEB3B)
        else -> ZoneClosed
    }
    val ts = event.timestamp.take(19).replace("T", " ")
    val valueStr = event.value
    val evLabel = buildString {
        append(prop.uppercase())
        if (valueStr == "true") append(" ON")
        else if (valueStr == "false") append(" OFF")
        else if (valueStr.isNotBlank() && valueStr != "true" && valueStr != "false") append(" $valueStr")
    }
    val typeIcon = if (event.type == "partition") "\uD83C\uDFDB" else "\uD83D\uDCE1"
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
                Text("$typeIcon ${event.label}", color = Color.White, fontSize = 13.sp)
                Text(ts, color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
            }
            Text(evLabel, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Alarm code dialog ──

@Composable
private fun AlarmCodeDialog(
    action: String,
    initialCode: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf(initialCode) }
    val title = when (action) {
        "arm_away" -> "Arm Away"
        "arm_stay" -> "Arm Home"
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
            TextButton(onClick = { onConfirm(code) }, enabled = code.length >= 4) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
