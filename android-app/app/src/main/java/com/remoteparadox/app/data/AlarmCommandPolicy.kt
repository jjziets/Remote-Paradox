package com.remoteparadox.app.data

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.Response

internal fun Response<ActionResult>.panelAccepted(): Boolean =
    isSuccessful && body()?.success == true

internal const val UNCONFIRMED_COMMAND =
    "Panel did not confirm the command. Check status before retrying."

internal object AlarmCommandGate {
    private val sending = AtomicReference<Lease?>(null)

    class Lease internal constructor() {
        fun finish(): Boolean = sending.compareAndSet(this, null)
    }

    fun tryAcquire(): Lease? {
        val lease = Lease()
        return lease.takeIf { sending.compareAndSet(null, it) }
    }

    val busy: Boolean
        get() = sending.get() != null
}

internal fun panicCommandAvailable(
    hasCredentials: Boolean,
    httpConfigured: Boolean,
    networkAvailable: Boolean,
    bleConnected: Boolean,
    commandBusy: Boolean,
): Boolean =
    hasCredentials && !commandBusy && ((httpConfigured && networkAvailable) || bleConnected)

internal fun launchAlarmCommand(
    scope: CoroutineScope,
    lease: AlarmCommandGate.Lease,
    completionLock: Any,
    onCompletion: () -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY, block = block)
    job.invokeOnCompletion { synchronized(completionLock) { if (lease.finish()) onCompletion() } }
    job.start()
    return job
}

internal data class RealtimeStatusTicket(val owner: String, val session: Long, val revision: Long)

internal fun AlarmStatus.confirmedStatus(): AlarmStatus? = takeIf {
    connected && partitions.isNotEmpty()
}

internal fun canSendAlarmCommand(name: String, status: AlarmStatus?): Boolean =
    name !in setOf("arm_away", "arm_stay", "disarm", "bypass", "unbypass") ||
        status?.confirmedStatus() != null

// Callers hold this monitor through both the decision and the corresponding UI update.
internal class RealtimeStatusPolicy {
    private var session = 0L
    private var revision = 0L
    private var active = false
    private var source: String? = null
    private var confirmedAt = 0L

    fun restart() {
        session++
        revision++
        active = true
        source = null
    }

    fun stop() {
        restart()
        active = false
    }

    fun capture(owner: String) = RealtimeStatusTicket(owner, session, revision)

    fun sameSession(ticket: RealtimeStatusTicket, owner: String): Boolean =
        active && ticket.owner == owner && ticket.session == session

    fun current(ticket: RealtimeStatusTicket, owner: String): Boolean =
        sameSession(ticket, owner) && ticket.revision == revision

    fun accept(
        ticket: RealtimeStatusTicket,
        owner: String,
        transport: String,
        now: Long,
        connected: Boolean,
    ): Boolean {
        if (!current(ticket, owner)) return false
        revision++
        source = transport.takeIf { connected }
        confirmedAt = now
        return true
    }

    fun fail(ticket: RealtimeStatusTicket, owner: String, transport: String, now: Long): Boolean {
        if (!current(ticket, owner)) return false
        if (source != null && source != transport && now - confirmedAt in 0 until 15_000L)
            return false
        invalidate()
        return true
    }

    fun expire(now: Long): Boolean {
        if (!active || source == null || now - confirmedAt in 0 until 15_000L) return false
        invalidate()
        return true
    }

    fun invalidate() {
        revision++
        source = null
    }
}

// HTTP and BLE fallback share one read slot and a cancellable realtime parent.
internal class StatusRefreshJobs(private val parent: kotlinx.coroutines.CoroutineScope) {
    private var scope: kotlinx.coroutines.CoroutineScope? = null
    private var read: kotlinx.coroutines.Job? = null

    @Synchronized
    fun start(): kotlinx.coroutines.CoroutineScope {
        stop()
        val job = kotlinx.coroutines.SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job])
        return kotlinx.coroutines.CoroutineScope(parent.coroutineContext + job).also { scope = it }
    }

    @Synchronized
    fun refresh(
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ): kotlinx.coroutines.Job? {
        val currentScope = scope ?: return null
        if (read?.isActive == true) return read
        return currentScope
            .launch(
                kotlinx.coroutines.Dispatchers.IO,
                start = kotlinx.coroutines.CoroutineStart.LAZY,
                block = block,
            )
            .also {
                read = it
                it.start()
            }
    }

    @Synchronized
    fun stop() {
        scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
        scope = null
        read = null
    }

    suspend fun awaitRead() {
        synchronized(this) { read }?.join()
    }
}

internal fun statusStreamStale(now: Long, lastStatusAt: Long): Boolean =
    now - lastStatusAt >= 15_000L
