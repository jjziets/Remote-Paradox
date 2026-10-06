package com.remoteparadox.app

import com.remoteparadox.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeStatusPolicyTest {
    @Test
    fun `late callback that ignores cancellation cannot overwrite resumed status`() = runBlocking {
        withTimeout(5_000) {
            val policy = RealtimeStatusPolicy().apply { restart() }
            val jobs = StatusRefreshJobs(this)
            jobs.start()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldTicket = policy.capture("owner")
            var visible = "armed"
            val oldRead =
                jobs.refresh {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        synchronized(policy) {
                            if (policy.accept(oldTicket, "owner", "ble", 200, true))
                                visible = "armed"
                        }
                    }
                }!!
            started.await()
            synchronized(policy) {
                policy.stop()
                policy.restart()
                policy.accept(policy.capture("owner"), "owner", "ws", 100, true)
                visible = "disarmed"
            }
            jobs.stop()
            jobs.start()
            try {
                release.complete(Unit)
                oldRead.join()
                assertTrue(oldRead.isCancelled)
                assertEquals("disarmed", synchronized(policy) { visible })
            } finally {
                jobs.stop()
            }
        }
    }

    private val owner = "https://alarm/|alice|certificate"

    private fun policy() = RealtimeStatusPolicy().apply { restart() }

    @Test
    fun `pausing status does not release an already dispatched control command`() {
        val policy = policy()
        val lease = AlarmCommandGate.tryAcquire()!!
        try {
            policy.stop()
            policy.restart()
            assertNull(AlarmCommandGate.tryAcquire())
        } finally {
            lease.finish()
        }
        AlarmCommandGate.tryAcquire()!!.finish()
    }

    @Test
    fun `normal commands reject unknown status while panic remains available`() {
        val partition = PartitionInfo(1, "House", true, "armed_away", zones = emptyList())
        val confirmed = AlarmStatus(listOf(partition), true)
        for (command in listOf("arm_away", "arm_stay", "disarm", "bypass", "unbypass")) {
            assertFalse(canSendAlarmCommand(command, null))
            assertFalse(canSendAlarmCommand(command, confirmed.copy(connected = false)))
            assertFalse(canSendAlarmCommand(command, AlarmStatus(emptyList(), true)))
            assertTrue(canSendAlarmCommand(command, confirmed))
        }
        assertTrue(canSendAlarmCommand("panic", null))
        assertTrue(canSendAlarmCommand("panic", confirmed.copy(connected = false)))
    }

    @Test
    fun `HTTP and BLE results started before newer websocket status are rejected`() {
        for (transport in listOf("http", "ble")) {
            val policy = policy()
            val oldRead = policy.capture(owner)
            assertTrue(policy.accept(policy.capture(owner), owner, "ws", 100, true))
            assertFalse(policy.accept(oldRead, owner, transport, 200, true))
            assertFalse(policy.fail(oldRead, owner, transport, 200))
        }
    }

    @Test
    fun `failed reads invalidate their own cached armed or disarmed status`() {
        for (transport in listOf("http", "ble", "ws")) {
            val policy = policy()
            assertTrue(policy.accept(policy.capture(owner), owner, transport, 100, true))
            val before = policy.capture(owner)
            assertTrue(policy.fail(before, owner, transport, 200))
            assertFalse(policy.current(before, owner))
        }
    }

    @Test
    fun `fresh other transport survives failure but never survives its TTL`() {
        for ((confirmed, failed) in listOf("ws" to "http", "ble" to "http", "http" to "ws")) {
            val policy = policy()
            policy.accept(policy.capture(owner), owner, confirmed, 100, true)
            val ticket = policy.capture(owner)
            assertFalse(policy.fail(ticket, owner, failed, 15_099))
            assertFalse(policy.expire(15_099))
            assertTrue(policy.expire(15_100))
            assertFalse(policy.accept(ticket, owner, failed, 15_101, true))
        }
    }

    @Test
    fun `other transport failure after TTL clears cached confirmation`() {
        val policy = policy()
        policy.accept(policy.capture(owner), owner, "ws", 100, true)
        assertTrue(policy.fail(policy.capture(owner), owner, "http", 15_100))
    }

    @Test
    fun `offline response fences outstanding reads without inferring disarmed`() {
        val policy = policy()
        val oldRead = policy.capture(owner)
        assertTrue(policy.accept(policy.capture(owner), owner, "ws", 100, false))
        assertFalse(policy.accept(oldRead, owner, "http", 200, true))
        assertFalse(policy.expire(20_000))
        assertNull(AlarmStatus(emptyList(), connected = false).confirmedStatus())
    }

    @Test
    fun `disconnected partitions and empty connected payload are unknown`() {
        val armed = PartitionInfo(1, "House", true, "armed_away", zones = emptyList())
        val disarmed = armed.copy(armed = false, mode = "disarmed")
        assertNull(AlarmStatus(listOf(armed), connected = false).confirmedStatus())
        assertNull(AlarmStatus(listOf(disarmed), connected = false).confirmedStatus())
        assertNull(AlarmStatus(emptyList(), connected = true).confirmedStatus())
        assertEquals(
            "armed_away",
            AlarmStatus(listOf(armed), true).confirmedStatus()?.partitions?.single()?.mode,
        )
        assertEquals(
            "disarmed",
            AlarmStatus(listOf(disarmed), true).confirmedStatus()?.partitions?.single()?.mode,
        )
    }

    @Test
    fun `stop rejects callbacks and reads until a new realtime session starts`() {
        val policy = policy()
        val ticket = policy.capture(owner)
        policy.stop()
        assertFalse(policy.sameSession(ticket, owner))
        assertFalse(policy.accept(policy.capture(owner), owner, "http", 100, true))
        policy.restart()
        assertFalse(policy.current(ticket, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test
    fun `same owner reconnect and account server certificate changes reject callbacks`() {
        val policy = policy()
        val ticket = policy.capture(owner)
        for (replacement in
            listOf(
                "https://other/|alice|certificate",
                "https://alarm/|bob|certificate",
                "https://alarm/|alice|new",
            )) {
            assertFalse(policy.accept(ticket, replacement, "http", 100, true))
            assertFalse(policy.fail(ticket, replacement, "ble", 100))
        }
        policy.restart()
        assertFalse(policy.accept(ticket, owner, "ble", 100, true))
    }

    @Test
    fun `command invalidation rejects prior reads until a fresh confirmation`() {
        val policy = policy()
        val ticket = policy.capture(owner)
        policy.invalidate()
        assertFalse(policy.accept(ticket, owner, "http", 100, true))
        assertTrue(policy.accept(policy.capture(owner), owner, "http", 200, true))
    }

    @Test
    fun `clock reversal cannot prolong cached status`() {
        val policy = policy()
        policy.accept(policy.capture(owner), owner, "http", 100, true)
        assertTrue(policy.expire(99))
    }

    @Test
    fun `refresh is single flight including child BLE fallback`() = runBlocking {
        withTimeout(5_000) {
            val jobs = StatusRefreshJobs(this)
            jobs.start()
            try {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var reads = 0
                val first =
                    jobs.refresh {
                        reads++
                        started.complete(Unit)
                        release.await()
                    }!!
                started.await()
                assertSame(first, jobs.refresh { fail("Overlapping status read") })
                release.complete(Unit)
                first.join()
                val next = jobs.refresh { reads++ }!!
                next.join()
                assertEquals(2, reads)
            } finally {
                jobs.stop()
            }
        }
    }

    @Test
    fun `stop cancels read children and polling and restart permits one new read`() = runBlocking {
        withTimeout(5_000) {
            val jobs = StatusRefreshJobs(this)
            val scope = jobs.start()
            val readStarted = CompletableDeferred<Unit>()
            val childStarted = CompletableDeferred<Unit>()
            val pollStarted = CompletableDeferred<Unit>()
            val read =
                jobs.refresh {
                    readStarted.complete(Unit)
                    launch {
                        childStarted.complete(Unit)
                        awaitCancellation()
                    }
                    awaitCancellation()
                }!!
            val poll =
                scope.launch {
                    pollStarted.complete(Unit)
                    awaitCancellation()
                }
            readStarted.await()
            childStarted.await()
            pollStarted.await()
            jobs.stop()
            read.join()
            poll.join()
            assertTrue(read.isCancelled)
            assertTrue(poll.isCancelled)
            assertNull(jobs.refresh { fail("Read while paused") })
            jobs.start()
            try {
                val fresh = jobs.refresh {}!!
                fresh.join()
                assertFalse(fresh.isCancelled)
            } finally {
                jobs.stop()
            }
        }
    }
}
