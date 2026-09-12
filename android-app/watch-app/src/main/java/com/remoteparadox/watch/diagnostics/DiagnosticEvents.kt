package com.remoteparadox.watch.diagnostics

import com.remoteparadox.watch.data.AlarmStatus
import com.remoteparadox.diagnostics.DiagnosticEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

internal object DiagnosticEvents {

    fun route(command: String): String? = when (command) {
        "arm_away" -> "/alarm/arm-away"
        "arm_stay" -> "/alarm/arm-stay"
        "disarm" -> "/alarm/disarm"
        "bypass", "unbypass" -> "/alarm/bypass"
        "panic" -> "/alarm/panic"
        "alarm_status" -> "/alarm/status"
        else -> null
    }

    fun error(cause: Throwable): String = when (cause) {
        is SocketTimeoutException, is TimeoutCancellationException -> "timeout"
        is CancellationException -> "cancelled"
        is SerializationException -> "parse"
        is IOException -> "connection"
        else -> "unknown"
    }

    fun summaries(status: AlarmStatus, source: String): List<DiagnosticEvent> {
        val parts = status.partitions.filter { it.id in 1..32 }.take(32)
        if (parts.isEmpty()) return listOf(DiagnosticEvent(kind = "status_received", source = source, connected = status.connected))
        return parts.map { p ->
            DiagnosticEvent(kind = "status_received", source = source, connected = status.connected, partitionId = p.id,
                mode = when (p.mode) {
                    "disarmed", "arming", "armed_away", "armed_home", "triggered" -> p.mode
                    "exit_delay" -> "arming"
                    else -> "unknown"
                },
                openZones = p.zones.count { it.open }.coerceAtMost(512),
                bypassedZones = p.zones.count { it.bypassed }.coerceAtMost(512))
        }
    }

    fun status(status: AlarmStatus, source: String, session: DiagnosticSession?) {
        if (session == null) return
        synchronized(ClientDiagnostics.lock) {
            if (!ClientDiagnostics.matches(session)) return
            // The shared recorder retains changes and a bounded periodic heartbeat.
            summaries(status, source).forEach { ClientDiagnostics.record(it, session) }
        }
    }

    suspend fun <T> command(
        name: String,
        source: String,
        session: DiagnosticSession?,
        accepted: (T) -> Boolean,
        send: suspend () -> T,
    ): T {
        val route = route(name)
        val started = System.nanoTime()
        ClientDiagnostics.record(DiagnosticEvent(kind = "command_requested", source = source, route = route), session)
        try {
            val result = send()
            ClientDiagnostics.record(DiagnosticEvent(kind = "command_finished", source = source, route = route,
                success = accepted(result), elapsedMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)), session)
            return result
        } catch (e: Exception) {
            ClientDiagnostics.record(DiagnosticEvent(kind = "command_finished", source = source, route = route,
                success = false, error = error(e), elapsedMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)), session)
            throw e
        }
    }
}
