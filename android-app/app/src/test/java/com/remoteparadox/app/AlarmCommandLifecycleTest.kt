package com.remoteparadox.app

import com.remoteparadox.app.data.AlarmCommandGate
import com.remoteparadox.app.data.launchAlarmCommand
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AlarmCommandLifecycleTest {
    @Test
    fun `already cancelled scope releases lease without entering command body`() = runBlocking {
        val parent = Job().apply { cancel() }
        val lease = AlarmCommandGate.tryAcquire()!!
        val completions = AtomicInteger()
        var dispatched = false
        val job =
            launchAlarmCommand(
                CoroutineScope(parent),
                lease,
                Any(),
                { completions.incrementAndGet() },
            ) {
                dispatched = true
            }
        job.join()
        assertFalse(dispatched)
        assertTrue(job.isCancelled)
        assertEquals(1, completions.get())
        val next = AlarmCommandGate.tryAcquire()!!
        try {
            assertFalse(lease.finish())
            assertNull(AlarmCommandGate.tryAcquire())
        } finally {
            next.finish()
        }
    }

    @Test
    fun `active cancellation completes exactly once and never replays dispatch`(): Unit = runBlocking {
        withTimeout(5_000) {
            val lease = AlarmCommandGate.tryAcquire()!!
            val parent = SupervisorJob()
            val started = CompletableDeferred<Unit>()
            val completions = AtomicInteger()
            val sends = AtomicInteger()
            val job =
                launchAlarmCommand(
                    CoroutineScope(parent),
                    lease,
                    Any(),
                    { completions.incrementAndGet() },
                ) {
                    sends.incrementAndGet()
                    started.complete(Unit)
                    awaitCancellation()
                }
            started.await()
            parent.cancel()
            job.join()
            assertEquals(1, sends.get())
            assertEquals(1, completions.get())
            assertFalse(lease.finish())
            AlarmCommandGate.tryAcquire()!!.finish()
        }
    }

    @Test
    fun `success and exception both release only the owning lease`() = runBlocking {
        for (fail in listOf(false, true)) {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + CoroutineExceptionHandler { _, _ -> })
            val lease = AlarmCommandGate.tryAcquire()!!
            val completions = AtomicInteger()
            val job =
                launchAlarmCommand(scope, lease, Any(), { completions.incrementAndGet() }) {
                    if (fail) throw IOException("Response lost")
                }
            job.join()
            assertEquals(1, completions.get())
            val next = AlarmCommandGate.tryAcquire()!!
            try {
                assertFalse(lease.finish())
                assertNull(AlarmCommandGate.tryAcquire())
            } finally {
                next.finish()
                parent.cancel()
            }
        }
    }
}
