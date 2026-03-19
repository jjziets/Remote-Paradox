package com.remoteparadox.watch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.remoteparadox.watch.PendingArm
import com.remoteparadox.watch.PartitionColor
import com.remoteparadox.watch.bypassedZones
import com.remoteparadox.watch.data.AlarmStatus
import com.remoteparadox.watch.data.PartitionInfo
import com.remoteparadox.watch.data.ZoneInfo
import com.remoteparadox.watch.isSplitScreen
import com.remoteparadox.watch.partitionDisplayColor
import com.remoteparadox.watch.statusLabel
import com.remoteparadox.watch.triggeredZoneNames
import com.remoteparadox.watch.ui.theme.*

@Composable
fun DashboardScreen(
    status: AlarmStatus?,
    isLoading: Boolean,
    actionInProgress: String?,
    error: String?,
    pendingArm: PendingArm?,
    onArmAway: (Int) -> Unit,
    onArmStay: (Int) -> Unit,
    onDisarm: (Int) -> Unit,
    onPanic: (Int) -> Unit,
    onBypassZone: (Int, Boolean) -> Unit,
    onBypassAllAndArm: () -> Unit,
    onDismissPendingArm: () -> Unit,
    onUnbypassZone: (Int) -> Unit,
    onLogout: () -> Unit,
) {
    if (status == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = error ?: "Connecting...",
                    textAlign = TextAlign.Center,
                    color = if (error != null) AlarmRed else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    BypassDialog(
        pendingArm = pendingArm,
        actionInProgress = actionInProgress,
        onBypassZone = onBypassZone,
        onBypassAllAndArm = onBypassAllAndArm,
        onDismiss = onDismissPendingArm,
    )

    val allBypassed = status.partitions.flatMap { bypassedZones(it) }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isSplitScreen(status)) {
            item {
                PartitionCard(
                    partition = status.partitions[0],
                    actionInProgress = actionInProgress,
                    onArmAway = onArmAway,
                    onArmStay = onArmStay,
                    onDisarm = onDisarm,
                    isSplit = true,
                )
            }
            item { Spacer(modifier = Modifier.height(4.dp)) }
            item {
                PartitionCard(
                    partition = status.partitions[1],
                    actionInProgress = actionInProgress,
                    onArmAway = onArmAway,
                    onArmStay = onArmStay,
                    onDisarm = onDisarm,
                    isSplit = true,
                )
            }
        } else {
            item {
                PartitionCard(
                    partition = status.partitions[0],
                    actionInProgress = actionInProgress,
                    onArmAway = onArmAway,
                    onArmStay = onArmStay,
                    onDisarm = onDisarm,
                    isSplit = false,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            PanicButton(
                partitionId = status.partitions.firstOrNull()?.id ?: 1,
                onPanic = onPanic,
            )
        }

        if (allBypassed.isNotEmpty()) {
            item { Spacer(modifier = Modifier.height(12.dp)) }
            item {
                Text(
                    text = "Bypassed Zones",
                    style = MaterialTheme.typography.titleSmall,
                    color = AlarmYellow,
                    fontWeight = FontWeight.Bold,
                )
            }
            for (zone in allBypassed) {
                item {
                    BypassedZoneRow(
                        zone = zone,
                        enabled = actionInProgress == null,
                        onUnbypass = { onUnbypassZone(zone.id) },
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            TextButton(
                onClick = onLogout,
            ) {
                Text("Logout", style = MaterialTheme.typography.bodySmall)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
fun PartitionCard(
    partition: PartitionInfo,
    actionInProgress: String?,
    onArmAway: (Int) -> Unit,
    onArmStay: (Int) -> Unit,
    onDisarm: (Int) -> Unit,
    isSplit: Boolean,
) {
    val color = partitionDisplayColor(partition.mode)
    val triggeredZones = triggeredZoneNames(partition)
    var showActions by remember { mutableStateOf(false) }

    val isPulsing = color == PartitionColor.ALARM_RED
    val pulseAlpha by if (isPulsing) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
    } else {
        remember { mutableFloatStateOf(1.0f) }
    }

    val isArming = partition.mode in listOf("arming", "exit_delay")

    val bgColor = when (color) {
        PartitionColor.GREEN -> AlarmGreen
        PartitionColor.RED -> AlarmRed
        PartitionColor.AMBER -> AlarmYellow
        PartitionColor.ALARM_RED -> AlarmPulseRed.copy(alpha = pulseAlpha)
    }

    val textColor = if (color == PartitionColor.AMBER) Color.Black else Color.White

    val animatedBg by animateColorAsState(
        targetValue = bgColor,
        animationSpec = tween(400),
        label = "bgAnim",
    )

    val cardHeight = if (isSplit) 70.dp else 140.dp

    ActionDialog(
        visible = showActions,
        partition = partition,
        actionInProgress = actionInProgress,
        onArmAway = { onArmAway(partition.id); showActions = false },
        onArmStay = { onArmStay(partition.id); showActions = false },
        onDisarm = { onDisarm(partition.id); showActions = false },
        onDismiss = { showActions = false },
    )

    val cardShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(cardShape)
            .background(animatedBg)
            .border(1.5.dp, Color.White.copy(alpha = 0.2f), cardShape)
            .clickable {
                if (isArming) {
                    onDisarm(partition.id)
                } else {
                    showActions = true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = partition.name,
                fontSize = if (isSplit) 18.sp else 28.sp,
                color = textColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isArming) "Arming… tap to cancel" else statusLabel(partition.mode),
                fontSize = if (isSplit) 13.sp else 16.sp,
                color = textColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (triggeredZones.isNotEmpty()) {
                Text(
                    text = triggeredZones.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (actionInProgress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
fun ActionDialog(
    visible: Boolean,
    partition: PartitionInfo,
    actionInProgress: String?,
    onArmAway: () -> Unit,
    onArmStay: () -> Unit,
    onDisarm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text(partition.name) },
    ) {
        if (partition.armed || partition.mode == "triggered") {
            item {
                Button(
                    onClick = onDisarm,
                    enabled = actionInProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmGreen),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disarm", fontSize = 14.sp)
                }
            }
        } else {
            item {
                Button(
                    onClick = onArmAway,
                    enabled = actionInProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmRed),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Arm Away", fontSize = 14.sp)
                }
            }
            item {
                Button(
                    onClick = onArmStay,
                    enabled = actionInProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmYellow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Arm Stay", fontSize = 14.sp)
                }
            }
        }
        item {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun PanicButton(
    partitionId: Int,
    onPanic: (Int) -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        visible = showConfirm,
        onDismissRequest = { showConfirm = false },
        title = { Text("PANIC", color = AlarmRed, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Send emergency panic alarm?",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onPanic(partitionId)
                    showConfirm = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = AlarmRed),
            ) {
                Text("CONFIRM", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { showConfirm = false }) {
                Text("Cancel")
            }
        },
    )

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(AlarmRed)
            .clickable { showConfirm = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "PANIC",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun BypassDialog(
    pendingArm: PendingArm?,
    actionInProgress: String?,
    onBypassZone: (Int, Boolean) -> Unit,
    onBypassAllAndArm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = pendingArm != null,
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Open Zones",
                fontWeight = FontWeight.Bold,
            )
        },
    ) {
        item {
            Text(
                text = "These zones are open and blocking arming:",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        pendingArm?.openZones?.forEach { zone ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = zone.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onBypassZone(zone.id, true) },
                        enabled = actionInProgress == null,
                        colors = ButtonDefaults.buttonColors(containerColor = AlarmYellow),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Bypass", fontSize = 11.sp, color = Color.Black)
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(4.dp)) }
        item {
            Button(
                onClick = onBypassAllAndArm,
                enabled = actionInProgress == null,
                colors = ButtonDefaults.buttonColors(containerColor = AlarmRed),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Bypass All & Arm", fontSize = 12.sp)
            }
        }
        item {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun BypassedZoneRow(
    zone: ZoneInfo,
    enabled: Boolean,
    onUnbypass: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = zone.name,
            style = MaterialTheme.typography.bodySmall,
            color = AlarmYellow,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onUnbypass,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
            modifier = Modifier.height(30.dp),
        ) {
            Text("Clear", fontSize = 10.sp, color = Color.White)
        }
    }
}
