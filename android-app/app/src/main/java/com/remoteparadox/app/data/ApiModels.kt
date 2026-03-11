package com.remoteparadox.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class ArmRequest(val code: String)

@Serializable
data class ActionResult(val success: Boolean, val action: String, val message: String = "")

@Serializable
data class ZoneInfo(val id: Int, val name: String, val open: Boolean)

@Serializable
data class AlarmStatus(
    val armed: Boolean,
    val mode: String,
    val zones: List<ZoneInfo>,
    val connected: Boolean,
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("alarm_connected") val alarmConnected: Boolean,
    @SerialName("websocket_clients") val websocketClients: Int,
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
