package com.remoteparadox.watch

import com.remoteparadox.watch.data.AlarmStatus
import com.remoteparadox.watch.data.PartitionInfo
import com.remoteparadox.watch.data.ZoneInfo

enum class PartitionColor { GREEN, RED, AMBER, ALARM_RED }

fun partitionDisplayColor(mode: String): PartitionColor = when (mode) {
    "disarmed" -> PartitionColor.GREEN
    "armed_away", "armed_home" -> PartitionColor.RED
    "arming", "entry_delay", "exit_delay" -> PartitionColor.AMBER
    "triggered" -> PartitionColor.ALARM_RED
    else -> PartitionColor.AMBER
}

fun statusLabel(mode: String): String = when (mode) {
    "disarmed" -> "Disarmed"
    "armed_away" -> "Armed Away"
    "armed_home" -> "Armed Home"
    "arming" -> "Arming"
    "triggered" -> "TRIGGERED"
    "entry_delay" -> "Entry Delay"
    "exit_delay" -> "Exit Delay"
    else -> mode
}

fun triggeredZoneNames(partition: PartitionInfo): List<String> =
    partition.zones.filter { it.alarm || it.wasInAlarm }.map { it.name }

fun openUnbypassedZones(partition: PartitionInfo): List<ZoneInfo> =
    partition.zones.filter { it.open && !it.bypassed }

fun bypassedZones(partition: PartitionInfo): List<ZoneInfo> =
    partition.zones.filter { it.bypassed }

fun isSplitScreen(status: AlarmStatus): Boolean = status.partitions.size >= 2
