package com.remoteparadox.diagnostics

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

object Diagnostics {
    @Volatile private var recorder: DiagnosticRecorder? = null

    @Synchronized
    fun initialize(directory: File, device: String, appVersion: String, buildCode: Long) {
        if (recorder != null) return
        try {
            require(appVersion.length <= 64)
            DeviceLog(device, appVersion, buildCode, 0)
            recorder = DiagnosticRecorder(File(directory, "structured-diagnostics-v1"), device, appVersion, buildCode)
        } catch (_: Exception) {
            // Diagnostics never prevents startup, including when storage is unavailable.
        }
    }

    fun scope(baseUrl: String?, username: String?): String? = try {
        if (baseUrl.isNullOrEmpty() || username.isNullOrEmpty() || baseUrl.length > 8192 || username.length > 4096) null
        else baseUrl.toHttpUrlOrNull()?.let { url ->
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) null
            else {
                val host = if (':' in url.host) "[${url.host}]" else url.host
                val canonical = "${url.scheme}://$host:${url.port}${url.encodedPath.trimEnd('/')}\n$username"
                MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
        }
    } catch (_: Exception) { null }

    val currentScope: String? get() = recorder?.currentScope
    fun bindScope(scope: String?) { recorder?.bindScope(scope) }
    fun record(event: DiagnosticEvent, scope: String? = currentScope) { recorder?.record(event, scope) }
    fun snapshot(maxBytes: Int = EXPORT_BYTES): DeviceLog {
        require(maxBytes >= 256) { "Diagnostic export budget must be at least 256 bytes" }
        return recorder?.snapshot(maxBytes)
            ?: DeviceLog("phone", "0.0.0", 1, SystemDiagnosticClock.wallMs(), truncated = true)
    }
    fun clear() { recorder?.clear() }
    fun interceptor(): Interceptor = DiagnosticHttpInterceptor(recorder)
}

internal data class ScopeToken(val scope: String?, val generation: Long)

internal class DiagnosticRecorder(
    directory: File,
    private val device: String,
    private val version: String,
    private val build: Long,
    private val clock: DiagnosticClock = SystemDiagnosticClock,
    queueCapacity: Int = 128,
    private val storeFactory: () -> RollingLogStore = { RollingLogStore(directory, clock) },
) : AutoCloseable {
    private data class Work(
        val token: ScopeToken,
        val action: (RollingLogStore) -> Unit,
        val snapshot: CompletableFuture<DeviceLog>? = null,
    )
    private val lock = Any()
    private val queue = ArrayBlockingQueue<Work>(queueCapacity)
    private var token = ScopeToken(null, 0)
    private var hasBound = false
    private var sequence = 0L
    private var lost = false
    private val processId = UUID.randomUUID().toString()
    @Volatile private var closed = false
    private val summaries = object : LinkedHashMap<String, Pair<DiagnosticEvent, Long>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<DiagnosticEvent, Long>>?) = size > 64
    }
    private val writer = Thread({ writeLoop() }, "client-diagnostics-writer").apply {
        isDaemon = true
        start()
    }

    val currentScope: String? get() = synchronized(lock) { token.scope }
    fun captureScope(): ScopeToken = synchronized(lock) { token }

    fun bindScope(scope: String?) {
        try {
            val valid = scope?.takeIf { Fields.scope.matches(it) }
            synchronized(lock) {
                if (closed || (valid != null && token.scope == valid)) return
                switchScope(valid, clear = false)
            }
        } catch (_: Exception) { }
    }

    fun clear() {
        try { synchronized(lock) { if (!closed) switchScope(token.scope, clear = true) } }
        catch (_: Exception) { }
    }

    private fun switchScope(scope: String?, clear: Boolean) {
        val erase = clear || hasBound || scope == null
        hasBound = true
        token = ScopeToken(scope, token.generation + 1)
        lost = false
        while (true) (queue.poll() ?: break).snapshot?.cancel(false)
        val next = token
        queue.offer(Work(next, { store ->
            summaries.clear()
            if (erase) store.clear(scope) else store.bind(scope)
        }))
    }

    fun record(event: DiagnosticEvent, scope: String?) {
        try {
            synchronized(lock) {
                if (scope == null || scope != token.scope) return
                enqueueEvent(event, token)
            }
        } catch (_: Exception) { }
    }

    fun recordCaptured(event: DiagnosticEvent, captured: ScopeToken) {
        try { synchronized(lock) { if (captured == token) enqueueEvent(event, captured) } }
        catch (_: Exception) { }
    }

    private fun enqueueEvent(event: DiagnosticEvent, captured: ScopeToken) {
        if (closed || captured.scope == null) return
        if (!Fields.valid(event, stamped = false) || sequence == Long.MAX_VALUE) { lost = true; return }
        val stamped = event.copy(timeMs = clock.wallMs(), monotonicMs = clock.monotonicMs(),
            processId = processId, sequence = ++sequence)
        // Leave room for an export barrier even when event producers saturate the queue.
        if (queue.remainingCapacity() <= 1) { lost = true; return }
        if (!queue.offer(Work(captured, { store ->
            if (retainSummary(stamped)) store.append(stamped)
        }))) lost = true
    }

    // Only summary fields participate; process stamps do not turn every poll into a change.
    private fun retainSummary(event: DiagnosticEvent): Boolean {
        if (event.kind != "status_received") return true
        val key = "${event.source}:${event.partitionId}:${event.zoneId}"
        val summary = event.copy(timeMs = 0, monotonicMs = 0, processId = "", sequence = 0,
            requestId = null, elapsedMs = null)
        val previous = summaries[key]
        if (previous != null && previous.first == summary && event.monotonicMs >= previous.second &&
            event.monotonicMs - previous.second < 60_000) return false
        summaries[key] = summary to event.monotonicMs
        return true
    }

    fun snapshot(maxBytes: Int = EXPORT_BYTES): DeviceLog {
        require(maxBytes >= 256) { "Diagnostic export budget must be at least 256 bytes" }
        val captured: ScopeToken
        val result = CompletableFuture<DeviceLog>()
        try {
            synchronized(lock) {
                captured = token
                if (closed || captured.scope == null) return empty()
                if (!queue.offer(Work(captured, { store ->
                    val dropped = synchronized(lock) { lost }
                    if (dropped) store.markTruncated()
                    result.complete(store.snapshot(device, version, build, maxBytes, dropped))
                }, result))) return empty(truncated = true)
            }
            val snapshot = result.get(2, TimeUnit.SECONDS)
            synchronized(lock) { return if (captured == token) snapshot else empty(truncated = true) }
        } catch (_: Exception) {
            result.cancel(false)
            return empty(truncated = true)
        }
    }

    private fun empty(truncated: Boolean = false) = DeviceLog(device, version, build,
        clock.wallMs().coerceAtLeast(0), truncated = truncated)

    private fun writeLoop() {
        var store: RollingLogStore? = try { storeFactory() } catch (_: Exception) { null }
        while (!closed) {
            val work = try { queue.take() } catch (_: InterruptedException) { continue }
            if (!synchronized(lock) { work.token == token } || work.snapshot?.isCancelled == true) {
                work.snapshot?.cancel(false)
                continue
            }
            try {
                val active = store ?: storeFactory().also { store = it }
                active.bind(work.token.scope)
                if (synchronized(lock) { work.token == token && lost }) active.markTruncated()
                work.action(active)
            } catch (_: Exception) {
                store = null
                synchronized(lock) { if (work.token == token) lost = true }
                work.snapshot?.completeExceptionally(IllegalStateException("Diagnostics unavailable"))
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            while (true) (queue.poll() ?: break).snapshot?.cancel(false)
        }
        writer.interrupt()
        writer.join(2000)
    }
}
