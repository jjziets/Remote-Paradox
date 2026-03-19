package com.remoteparadox.watch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String, val role: String)

@Serializable
data class ArmRequest(
    val code: String,
    @SerialName("partition_id") val partitionId: Int = 1,
)

@Serializable
data class PanicRequest(
    @SerialName("partition_id") val partitionId: Int = 1,
    @SerialName("panic_type") val panicType: String = "emergency",
)

@Serializable
data class BypassRequest(
    @SerialName("zone_id") val zoneId: Int,
    val bypass: Boolean,
)

@Serializable
data class ActionResult(val success: Boolean, val action: String = "", val message: String = "")

@Serializable
data class ZoneInfo(
    val id: Int,
    val name: String,
    val open: Boolean,
    val bypassed: Boolean = false,
    @SerialName("partition_id") val partitionId: Int = 1,
    val alarm: Boolean = false,
    @SerialName("was_in_alarm") val wasInAlarm: Boolean = false,
    val tamper: Boolean = false,
)

@Serializable
data class PartitionInfo(
    val id: Int,
    val name: String,
    val armed: Boolean,
    val mode: String,
    @SerialName("entry_delay") val entryDelay: Boolean = false,
    val ready: Boolean = true,
    val zones: List<ZoneInfo> = emptyList(),
)

@Serializable
data class AlarmStatus(
    val partitions: List<PartitionInfo>,
    val connected: Boolean,
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("alarm_connected") val alarmConnected: Boolean,
    @SerialName("websocket_clients") val websocketClients: Int,
    @SerialName("demo_mode") val demoMode: Boolean = false,
)
