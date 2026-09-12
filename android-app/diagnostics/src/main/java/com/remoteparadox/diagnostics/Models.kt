package com.remoteparadox.diagnostics

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

object DiagnosticCodec {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable(with = DiagnosticEventSerializer::class)
data class DiagnosticEvent(
    val kind: String,
    val source: String,
    val timeMs: Long = 0,
    val monotonicMs: Long = 0,
    val processId: String = "",
    val sequence: Long = 0,
    val requestId: String? = null,
    val route: String? = null,
    val partitionId: Int? = null,
    val zoneId: Int? = null,
    val httpStatus: Int? = null,
    val elapsedMs: Long? = null,
    val success: Boolean? = null,
    val connected: Boolean? = null,
    val mode: String? = null,
    val openZones: Int? = null,
    val bypassedZones: Int? = null,
    val error: String? = null,
)

@Serializable
data class DeviceLog(
    val device: String,
    val appVersion: String,
    val buildCode: WireLong,
    val capturedAtMs: WireLong,
    val truncated: WireBoolean = false,
    val events: List<DiagnosticEvent> = emptyList(),
) {
    init {
        require(device in Fields.devices && appVersion.length <= 64 && Fields.version.matches(appVersion))
        require(buildCode > 0 && capturedAtMs >= 0)
    }
}

@Serializable
data class DiagnosticReport(
    val reportId: String,
    val watchStatus: String,
    val phone: DeviceLog,
    val watch: DeviceLog? = null,
    val schemaVersion: WireInt = 1,
) {
    init {
        require(schemaVersion == 1 && Fields.uuid.matches(reportId))
        require(phone.device == "phone" && (watch == null || watch.device == "watch"))
        require(watchStatus in setOf("included", "unavailable", "scope_mismatch"))
        require((watchStatus == "included") == (watch != null))
    }
}

@Serializable
data class DiagnosticReceipt(
    val reportId: String,
    val receivedAtMs: WireLong,
    val expiresAtMs: WireLong,
    val sources: List<String>,
) {
    init {
        require(Fields.uuid.matches(reportId) && receivedAtMs >= 0 && expiresAtMs >= receivedAtMs)
        require(sources.isNotEmpty() && sources.all { it in Fields.devices })
        require(sources.distinct().size == sources.size)
    }
}

@Serializable
data class WatchLogRequest(val reportId: String, val scope: String) {
    init { require(Fields.uuid.matches(reportId) && Fields.scope.matches(scope)) }
}

@Serializable
data class WatchLogReply(
    val reportId: String,
    val scope: String,
    val status: String,
    val log: DeviceLog? = null,
) {
    init {
        require(Fields.uuid.matches(reportId) && Fields.scope.matches(scope))
        require(status in setOf("included", "scope_mismatch"))
        require((status == "included") == (log != null))
        require(log == null || log.device == "watch")
    }
}

internal object Fields {
    val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val scope = Regex("[0-9a-f]{64}")
    val version = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
    val devices = setOf("phone", "watch")
    val kinds = setOf("app_start", "foreground", "background", "command_requested", "command_finished",
        "http_started", "http_finished", "http_failed", "status_received", "ws_open", "ws_closed",
        "ws_failed", "tile_render", "tile_action", "report_requested")
    val sources = setOf("phone_app", "watch_app", "watch_tile", "http", "ws", "ble", "system")
    val routes = setOf("/alarm/arm-away", "/alarm/arm-stay", "/alarm/disarm", "/alarm/bypass",
        "/alarm/panic", "/alarm/status", "/ws")
    val modes = setOf("disarmed", "arming", "armed_away", "armed_home", "triggered", "unknown")
    val errors = setOf("timeout", "connection", "http", "parse", "cancelled", "unknown")

    fun valid(event: DiagnosticEvent, stamped: Boolean): Boolean = with(event) {
        kind in kinds && source in sources && (requestId == null || uuid.matches(requestId)) &&
            (route == null || route in routes) && (partitionId == null || partitionId in 1..32) &&
            (zoneId == null || zoneId in 1..512) && (httpStatus == null || httpStatus in 100..599) &&
            (elapsedMs == null || elapsedMs >= 0) && (mode == null || mode in modes) &&
            (openZones == null || openZones in 0..512) && (bypassedZones == null || bypassedZones in 0..512) &&
            (error == null || error in errors) &&
            (!stamped || (timeMs >= 0 && monotonicMs >= 0 && sequence > 0 && uuid.matches(processId)))
    }
}

// A separate wire representation rejects incomplete stamps on decode while callers can
// construct unstamped events for the recorder. No unstructured property is accepted.
@Serializable
internal data class EventWire(
    val timeMs: WireLong, val monotonicMs: WireLong, val processId: String, val sequence: WireLong,
    val kind: String, val source: String, val requestId: String? = null, val route: String? = null,
    val partitionId: WireInt? = null, val zoneId: WireInt? = null, val httpStatus: WireInt? = null,
    val elapsedMs: WireLong? = null, val success: WireBoolean? = null, val connected: WireBoolean? = null,
    val mode: String? = null, val openZones: WireInt? = null, val bypassedZones: WireInt? = null,
    val error: String? = null,
) {
    fun event() = DiagnosticEvent(kind, source, timeMs, monotonicMs, processId, sequence,
        requestId, route, partitionId, zoneId, httpStatus, elapsedMs, success, connected, mode,
        openZones, bypassedZones, error)
}

internal object DiagnosticEventSerializer : KSerializer<DiagnosticEvent> {
    override val descriptor: SerialDescriptor = EventWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: DiagnosticEvent) {
        if (!Fields.valid(value, stamped = true)) throw SerializationException("Invalid diagnostic event")
        with(value) {
            encoder.encodeSerializableValue(EventWire.serializer(), EventWire(timeMs, monotonicMs,
                processId, sequence, kind, source, requestId, route, partitionId, zoneId, httpStatus,
                elapsedMs, success, connected, mode, openZones, bypassedZones, error))
        }
    }

    override fun deserialize(decoder: Decoder): DiagnosticEvent {
        val event = decoder.decodeSerializableValue(EventWire.serializer()).event()
        if (!Fields.valid(event, stamped = true)) throw SerializationException("Invalid diagnostic event")
        return event
    }
}
