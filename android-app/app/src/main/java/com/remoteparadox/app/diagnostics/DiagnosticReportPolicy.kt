package com.remoteparadox.app.diagnostics

import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val REQUEST_PATH = "/paradox/diagnostics/request"
internal const val RESPONSE_PATH = "/paradox/diagnostics/response"
internal const val REPORT_TTL_MS = 24 * 60 * 60 * 1000L
internal const val REPORT_MAX_BYTES = 128 * 1024
internal const val PENDING_CLEANUP_INTERVAL_MS = 60_000L

internal data class WatchCollection(val status: String, val log: DeviceLog? = null)

internal interface DiagnosticWearTransport {
    suspend fun nodes(): List<String>
    suspend fun addListener(listener: (String, String, ByteArray) -> Unit)
    fun removeListener(listener: (String, String, ByteArray) -> Unit)
    suspend fun send(node: String, payload: ByteArray)
}

internal class WatchReportCollector(private val transport: DiagnosticWearTransport) {
    suspend fun collect(reportId: String, scope: String): WatchCollection {
        val reply = CompletableDeferred<DeviceLog>()
        val guard = Any()
        var allowedNodes = emptySet<String>()
        var mismatch = false
        var closed = false
        val listener: (String, String, ByteArray) -> Unit = listener@{ node, path, bytes ->
            synchronized(guard) {
                if (closed || node !in allowedNodes || path != RESPONSE_PATH || bytes.size > 64 * 1024) return@listener
                val value = runCatching { DiagnosticCodec.json.decodeFromString<WatchLogReply>(bytes.decodeToString(throwOnInvalidSequence = true)) }
                    .getOrNull() ?: return@listener
                if (value.reportId != reportId || value.scope != scope) return@listener
                when {
                    value.status == "included" && value.log?.device == "watch" -> reply.complete(value.log!!)
                    value.status == "scope_mismatch" && value.log == null -> mismatch = true
                }
            }
        }
        try {
            val log = withTimeoutOrNull(10_000) {
                transport.addListener(listener)
                val nodes = transport.nodes().distinct().take(4)
                synchronized(guard) { allowedNodes = nodes.toSet() }
                if (nodes.isEmpty()) return@withTimeoutOrNull null
                val request = DiagnosticCodec.json.encodeToString(WatchLogRequest(reportId, scope)).toByteArray()
                require(request.size <= 256)
                coroutineScope {
                    val sends = nodes.map { node -> launch {
                        try { transport.send(node, request) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Other nodes may still respond. */ }
                    } }
                    try { reply.await() } finally { sends.forEach { it.cancel() } }
                }
            }
            return if (log != null) WatchCollection("included", log)
                else WatchCollection(if (synchronized(guard) { mismatch }) "scope_mismatch" else "unavailable")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return WatchCollection(if (synchronized(guard) { mismatch }) "scope_mismatch" else "unavailable")
        } finally {
            synchronized(guard) { closed = true }
            runCatching { transport.removeListener(listener) }
        }
    }
}

@Serializable
internal data class PendingDiagnostic(val scope: String, val createdAtMs: Long, val body: String)

internal class PendingDiagnosticStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file get() = File(directory, "pending.json")

    @Synchronized fun cleanup(scope: String?): PendingDiagnostic? {
        // Save holds this same monitor, so any temporary file seen here is orphaned.
        File(directory, "pending.tmp").delete()
        return read(scope)
    }

    @Synchronized fun read(scope: String?): PendingDiagnostic? {
        if (!file.exists()) return null
        val pending = runCatching {
            require(file.length() <= 300 * 1024)
            DiagnosticCodec.json.decodeFromString<PendingDiagnostic>(file.readText())
        }.getOrNull()
        if (pending == null || pending.scope != scope || now() - pending.createdAtMs !in 0 until REPORT_TTL_MS) {
            clear()
            return null
        }
        return pending
    }

    @Synchronized fun save(pending: PendingDiagnostic) {
        require(pending.body.toByteArray().size <= REPORT_MAX_BYTES)
        require(now() - pending.createdAtMs in 0 until REPORT_TTL_MS)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "pending.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(DiagnosticCodec.json.encodeToString(pending).toByteArray())
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    @Synchronized fun clear() {
        File(directory, "pending.tmp").delete()
        file.delete()
    }
}

internal fun validateReceipt(body: String, report: DiagnosticReport): DiagnosticReceipt {
    val receipt = DiagnosticCodec.json.decodeFromString<DiagnosticReceipt>(body)
    require(receipt.reportId == report.reportId)
    require(UUID.fromString(receipt.reportId).toString() == receipt.reportId)
    val expected = if (report.watchStatus == "included") setOf("phone", "watch") else setOf("phone")
    require(receipt.sources.size == expected.size && receipt.sources.toSet() == expected)
    require(receipt.receivedAtMs > 0 && receipt.expiresAtMs > receipt.receivedAtMs)
    require(receipt.expiresAtMs - receipt.receivedAtMs <= REPORT_TTL_MS)
    return receipt
}
