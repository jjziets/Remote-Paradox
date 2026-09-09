package com.remoteparadox.watch.data

import android.content.Context
import java.io.IOException
import kotlinx.serialization.encodeToString

internal data class StatusCacheTicket(val owner: String, val session: Long, val command: Long)

// All cache instances share this guard. Callers hold its monitor through storage operations.
internal class StatusCachePolicy {
    private var session = 0L
    private var command = 0L
    private var sending = false

    fun capture(owner: String) = StatusCacheTicket(owner, session, command)
    fun sameSession(ticket: StatusCacheTicket, owner: String) =
        ticket.owner == owner && ticket.session == session
    fun current(ticket: StatusCacheTicket, owner: String) =
        sameSession(ticket, owner) && ticket.command == command && !sending

    fun newSession() {
        session++
        command++
        sending = false
    }

    fun beginCommand(ticket: StatusCacheTicket, owner: String): StatusCacheTicket? {
        if (!current(ticket, owner)) return null
        sending = true
        command++
        return capture(owner)
    }

    fun finishCommand(ticket: StatusCacheTicket, owner: String): Boolean {
        if (!sameSession(ticket, owner) || ticket.command != command || !sending) return false
        sending = false
        command++ // Reject reads started while the command was in flight as well.
        return true
    }

    suspend fun <T> command(
        ticket: StatusCacheTicket,
        owner: () -> String,
        invalidate: () -> Unit,
        send: suspend () -> T,
    ): T? {
        val started = synchronized(this) {
            val started = beginCommand(ticket, owner()) ?: return null
            try {
                invalidate()
            } catch (e: Exception) {
                finishCommand(started, owner())
                throw e
            }
            started
        }
        try {
            return send()
        } finally {
            synchronized(this) { finishCommand(started, owner()) }
        }
    }
}

internal class WatchStatusCache(context: Context, private val tokens: WatchTokenStore) {
    private val prefs = context.getSharedPreferences("paradox_watch_status", Context.MODE_PRIVATE)
    private val owner: String get() = "${tokens.baseUrl}|${tokens.username}|${tokens.certFingerprint}"

    fun capture(): StatusCacheTicket = synchronized(policy) { policy.capture(owner) }
    fun isSameSession(ticket: StatusCacheTicket): Boolean = synchronized(policy) {
        policy.sameSession(ticket, owner)
    }
    fun isCurrent(ticket: StatusCacheTicket): Boolean = synchronized(policy) {
        tokens.isLoggedIn && policy.current(ticket, owner)
    }

    fun <T> inSession(ticket: StatusCacheTicket, block: () -> T): T? = synchronized(policy) {
        if (policy.sameSession(ticket, owner)) block() else null
    }

    fun <T> ifCurrent(ticket: StatusCacheTicket, block: () -> T): T? = synchronized(policy) {
        if (isCurrent(ticket)) block() else null
    }

    fun read(ticket: StatusCacheTicket = capture()): StatusSnapshot? = synchronized(policy) {
        if (!isCurrent(ticket) || prefs.getString("owner", null) != ticket.owner) return null
        try {
            prefs.getString("snapshot", null)?.let { ApiClient.json.decodeFromString<StatusSnapshot>(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: StatusSnapshot, ticket: StatusCacheTicket): Boolean = synchronized(policy) {
        if (!isCurrent(ticket)) return false
        val existing = read(ticket)?.takeIf { it.isFresh(System.currentTimeMillis()) }
        if ((existing?.receivedAt ?: Long.MIN_VALUE) > snapshot.receivedAt) return false
        prefs.edit().putString("owner", ticket.owner)
            .putString("snapshot", ApiClient.json.encodeToString(snapshot)).apply()
        true
    }

    fun replaceSession(updateCredentials: () -> Unit = {}): StatusCacheTicket = synchronized(policy) {
        policy.newSession()
        prefs.edit().clear().apply()
        updateCredentials()
        capture()
    }

    fun clear() { replaceSession() }

    suspend fun <T> command(ticket: StatusCacheTicket, send: suspend () -> T): T? =
        policy.command(ticket, { owner }, {
            // Flush invalidation before dispatch so process death cannot resurrect pre-command data.
            if (!prefs.edit().clear().commit()) {
                throw IOException("Could not invalidate alarm status")
            }
        }, send)

    companion object {
        private val policy = StatusCachePolicy()
    }
}
