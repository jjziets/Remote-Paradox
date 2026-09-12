package com.remoteparadox.app.diagnostics

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.remoteparadox.app.data.TokenStore
import com.remoteparadox.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DiagnosticReportState(
    val busy: Boolean = false,
    val reportId: String? = null,
    val watchStatus: String? = null,
    val message: String? = null,
    val pending: Boolean = false,
    val receiptValidated: Boolean = false,
) {
    val identifierLabel: String get() = if (receiptValidated && !pending) "Report ID" else "Capture ID"
}

internal class DiagnosticReportCoordinator(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val target: () -> DiagnosticTarget?,
    private val matches: (DiagnosticTarget) -> Boolean,
    private val store: PendingDiagnosticStore,
    private val watch: suspend (String, String) -> WatchCollection,
    private val snapshot: () -> DeviceLog,
    private val upload: suspend (DiagnosticTarget, String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(DiagnosticReportState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var operationGeneration = 0L
    private var maintenanceJob: Job? = null

    fun restore() {
        val generation = synchronized(lock) { operationGeneration }
        scope.launch {
            val runningJob = currentCoroutineContext().job
            synchronized(lock) {
                if (runningJob.isActive && generation == operationGeneration) cleanupPendingLocked()
            }
        }
    }

    fun startMaintenance() = synchronized(lock) {
        if (maintenanceJob?.isActive == true) return@synchronized
        maintenanceJob = scope.launch {
            while (isActive) {
                synchronized(lock) { if (isActive) cleanupPendingLocked() }
                delay(PENDING_CLEANUP_INTERVAL_MS)
            }
        }
    }

    private fun cleanupPendingLocked() {
        val pending = runCatching { store.cleanup(target()?.session?.scope) }.getOrElse { return }
        val report = pending?.let { runCatching { DiagnosticCodec.json.decodeFromString<DiagnosticReport>(it.body) }.getOrNull() }
        if (pending != null && report == null) runCatching { store.clear() }
        if (report == null && mutableState.value.pending) {
            invalidateOperationLocked()
            mutableState.value = DiagnosticReportState(message = "Saved capture expired or is no longer available.")
        } else if (report != null && !mutableState.value.busy && !mutableState.value.pending) {
            mutableState.value = DiagnosticReportState(reportId = report.reportId, watchStatus = report.watchStatus,
                message = "Saved report available for retry", pending = true)
        }
    }

    private fun invalidateOperationLocked() {
        operationGeneration++
        job?.cancel()
        job = null
    }

    fun reset() = synchronized(lock) {
        invalidateOperationLocked()
        runCatching { store.clear() }
        mutableState.value = DiagnosticReportState()
    }

    fun send(retry: Boolean = false): Unit = synchronized(lock) {
        if (!scope.isActive) return@synchronized
        cleanupPendingLocked()
        if (mutableState.value.busy) return@synchronized
        if (!retry && mutableState.value.pending) return@synchronized
        val selected = target()
        if (selected == null) {
            mutableState.value = DiagnosticReportState(message = "Sign in before collecting diagnostics")
            return@synchronized
        }
        val generation = ++operationGeneration
        mutableState.value = if (retry) mutableState.value.copy(busy = true, message = "Uploading saved report")
            else DiagnosticReportState(busy = true, message = "Collecting phone and watch diagnostics")
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext().job
            try {
                val pending = if (retry) {
                    synchronized(lock) {
                        ensureCurrent(selected, generation, runningJob)
                        store.read(selected.session.scope)
                    } ?: error("Saved report expired")
                } else {
                    val reportId = UUID.randomUUID().toString()
                    synchronized(lock) {
                        ensureCurrent(selected, generation, runningJob)
                        ClientDiagnostics.record(DiagnosticEvent(kind = "report_requested", source = "phone_app"), selected.session)
                    }
                    val companion = watch(reportId, selected.session.scope)
                    ensureCurrent(selected, generation, runningJob)
                    val phone = snapshot()
                    ensureCurrent(selected, generation, runningJob)
                    val report = DiagnosticReport(reportId = reportId, watchStatus = companion.status, phone = phone, watch = companion.log)
                    val body = DiagnosticCodec.json.encodeToString(report)
                    require(body.toByteArray().size <= REPORT_MAX_BYTES)
                    val captured = PendingDiagnostic(selected.session.scope, now(), body)
                    synchronized(lock) {
                        ensureCurrent(selected, generation, runningJob)
                        store.save(captured)
                    }
                    captured
                }
                val report = DiagnosticCodec.json.decodeFromString<DiagnosticReport>(pending.body)
                synchronized(lock) {
                    ensureCurrent(selected, generation, runningJob)
                    mutableState.value = DiagnosticReportState(true, report.reportId, report.watchStatus, "Uploading diagnostic report", true)
                }
                val receipt = validateReceipt(upload(selected, pending.body), report)
                synchronized(lock) {
                    ensureCurrent(selected, generation, runningJob)
                    store.clear()
                    mutableState.value = DiagnosticReportState(reportId = receipt.reportId, watchStatus = report.watchStatus,
                        message = "Report received by server", receiptValidated = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                synchronized(lock) {
                    if (isCurrent(selected, generation, runningJob)) {
                        val pending = runCatching { store.read(selected.session.scope) }.getOrNull()
                        mutableState.value = if (pending != null) mutableState.value.copy(busy = false, pending = true,
                            message = "Upload not confirmed. Report saved privately for retry (24 hours).")
                        else DiagnosticReportState(message = "Could not save the report, or the saved report expired.")
                    }
                }
            } finally {
                synchronized(lock) {
                    if (isCurrent(selected, generation, runningJob)) {
                        mutableState.value = mutableState.value.copy(busy = false)
                        job = null
                    }
                }
            }
        }
        job = next
        next.start()
    }

    private fun isCurrent(selected: DiagnosticTarget, generation: Long, runningJob: Job): Boolean =
        generation == operationGeneration && job === runningJob && runningJob.isActive && matches(selected)

    private fun ensureCurrent(selected: DiagnosticTarget, generation: Long, runningJob: Job) = synchronized(lock) {
        if (!isCurrent(selected, generation, runningJob)) throw CancellationException("Diagnostic operation superseded")
    }
}

internal object PhoneDiagnosticReports {
    private lateinit var coordinator: DiagnosticReportCoordinator
    val state get() = coordinator.state

    fun initialize(context: Context) {
        val app = context.applicationContext
        val tokens = TokenStore(app)
        val transport = object : DiagnosticWearTransport {
            val messages by lazy { Wearable.getMessageClient(app) }
            val installed = ConcurrentHashMap<(String, String, ByteArray) -> Unit, MessageClient.OnMessageReceivedListener>()
            override suspend fun nodes() = Wearable.getNodeClient(app).connectedNodes.await().map { it.id }
            override suspend fun addListener(listener: (String, String, ByteArray) -> Unit) {
                val callback = MessageClient.OnMessageReceivedListener { event ->
                    listener(event.sourceNodeId, event.path, event.data)
                }
                installed[listener] = callback
                val registration = messages.addListener(callback)
                registration.addOnCompleteListener {
                    // Registration can finish after timeout/account-switch cleanup.
                    if (installed[listener] !== callback) messages.removeListener(callback)
                }
                registration.await()
            }
            override fun removeListener(listener: (String, String, ByteArray) -> Unit) {
                installed.remove(listener)?.let { messages.removeListener(it) }
            }
            override suspend fun send(node: String, payload: ByteArray) {
                messages.sendMessage(node, REQUEST_PATH, payload).await()
            }
        }
        coordinator = DiagnosticReportCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.IO), ClientDiagnostics.lock,
            target = {
                synchronized(ClientDiagnostics.lock) {
                    val session = ClientDiagnostics.capture()
                    val base = tokens.baseUrl
                    if (session == null || base == null || tokens.token.isNullOrBlank()) null
                    else DiagnosticTarget(session, base, tokens.certFingerprint.orEmpty(), tokens.bearerHeader)
                }
            },
            matches = { ClientDiagnostics.matches(it.session) },
            store = PendingDiagnosticStore(File(app.noBackupFilesDir, "pending-diagnostics")),
            watch = WatchReportCollector(transport)::collect,
            snapshot = { Diagnostics.snapshot(48 * 1024) },
            upload = ::uploadDiagnostic,
        )
        ClientDiagnostics.onReset = coordinator::reset
        coordinator.startMaintenance()
    }

    fun send() = coordinator.send()
    fun retry() = coordinator.send(retry = true)
    fun forget() = coordinator.reset()
    fun onForeground() = coordinator.restore()
}
