package com.remoteparadox.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String, val role: String)

@Serializable
data class RegisterRequest(
    @SerialName("invite_code") val inviteCode: String,
    val username: String,
    val password: String,
)

@Serializable
data class RegisterResponse(val token: String, val username: String)

@Serializable
data class ArmRequest(
    val code: String,
    @SerialName("partition_id") val partitionId: Int = 1,
)

@Serializable
data class BypassRequest(
    @SerialName("zone_id") val zoneId: Int,
    val bypass: Boolean,
)

@Serializable
data class ActionResult(val success: Boolean, val action: String, val message: String = "")

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
    val zones: List<ZoneInfo>,
)

@Serializable
data class AlarmStatus(
    val partitions: List<PartitionInfo>,
    val connected: Boolean,
)

@Serializable
data class PanicRequest(
    @SerialName("partition_id") val partitionId: Int = 1,
    @SerialName("panic_type") val panicType: String = "emergency",
)

object AnyValueAsString : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("AnyValue", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive) element.content else element.toString()
    }
}

@Serializable
data class PanelEvent(
    val type: String,
    val label: String,
    val property: String,
    @Serializable(with = AnyValueAsString::class)
    val value: String,
    val timestamp: String,
    val user: String? = null,
)

@Serializable
data class EventHistoryResponse(val events: List<PanelEvent>)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("alarm_connected") val alarmConnected: Boolean,
    @SerialName("websocket_clients") val websocketClients: Int,
    @SerialName("demo_mode") val demoMode: Boolean = false,
)

@Serializable
data class AuditEntry(
    val timestamp: String,
    val username: String,
    val action: String,
    val detail: String? = null,
)

@Serializable
data class AuditLogResponse(val entries: List<AuditEntry>)

@Serializable
data class UserInfo(
    val username: String,
    val role: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class UserListResponse(val users: List<UserInfo>)

@Serializable
data class RoleUpdateRequest(val role: String)

@Serializable
data class InviteResponse(
    val uri: String,
    @SerialName("qr_data_uri") val qrDataUri: String = "",
    @SerialName("expires_in") val expiresIn: Int = 900,
)

@Serializable
data class PiUpdateStatus(
    val pending: Boolean = false,
    @SerialName("current_version") val currentVersion: String = "0.0.0",
    @SerialName("new_version") val newVersion: String? = null,
)

@Serializable
data class ErrorResponse(val detail: String)

data class ServerConfig(
    val host: String,
    val port: Int,
    val inviteCode: String,
    val fingerprint: String = "",
) {
    val baseUrl: String get() = "https://$host:$port/"

    companion object {
        fun fromUri(uri: String): ServerConfig? {
            val cleaned = uri.removePrefix("paradox://")
            val parts = cleaned.split("#", limit = 2)
            if (parts.size != 2) return null
            val hostPort = parts[0].split(":", limit = 2)
            if (hostPort.size != 2) return null
            val port = hostPort[1].toIntOrNull() ?: return null
            val fragment = parts[1]
            val fragParts = fragment.split(":", limit = 2)
            val code = fragParts[0]
            val fingerprint = if (fragParts.size > 1) fragParts[1] else ""
            return ServerConfig(
                host = hostPort[0],
                port = port,
                inviteCode = code,
                fingerprint = fingerprint,
            )
        }
    }
}
