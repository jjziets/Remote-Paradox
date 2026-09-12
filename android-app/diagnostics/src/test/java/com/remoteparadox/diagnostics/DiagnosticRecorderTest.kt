package com.remoteparadox.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticRecorderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = TestClock()
    private fun recorder() = DiagnosticRecorder(temporary.newFolder(), "phone", "1.2.3", 1, clock)
    private fun event(kind: String = "foreground") = DiagnosticEvent(kind, "phone_app")

    @Test fun `snapshot drains prior writes and replaces caller stamps`() {
        recorder().use { recorder ->
            recorder.bindScope(SCOPE_A)
            repeat(30) { recorder.record(event().copy(timeMs = 1, processId = "secret", sequence = -1), SCOPE_A) }
            val events = recorder.snapshot().events
            assertEquals(30, events.size)
            assertEquals((1L..30L).toList(), events.map { it.sequence })
            assertTrue(events.all { it.timeMs == clock.wall && it.monotonicMs == clock.monotonic })
            assertTrue(Fields.uuid.matches(events.first().processId))
            assertEquals(1, events.map { it.processId }.distinct().size)
        }
    }

    @Test fun `invalid events and mismatched or null scopes never reach storage`() {
        recorder().use { recorder ->
            recorder.bindScope(SCOPE_A)
            recorder.record(event(), null)
            recorder.record(event(), SCOPE_B)
            recorder.record(event().copy(mode = "private room"), SCOPE_A)
            recorder.record(event().copy(route = "/alarm/status?token=secret"), SCOPE_A)
            assertTrue(recorder.snapshot().events.isEmpty())
            assertTrue(recorder.snapshot().truncated)
        }
    }

    @Test fun `clear invalidates captured callbacks even for the same account`() {
        recorder().use { recorder ->
            recorder.bindScope(SCOPE_A)
            val before = recorder.captureScope()
            recorder.record(event(), SCOPE_A)
            assertEquals(1, recorder.snapshot().events.size)
            recorder.clear()
            recorder.recordCaptured(event(), before)
            assertTrue(recorder.snapshot().events.isEmpty())
            recorder.record(event(), SCOPE_A)
            assertEquals(1, recorder.snapshot().events.size)
        }
    }

    @Test fun `persistent same scope survives process restart with new process identity`() {
        val directory = temporary.newFolder()
        var oldProcess = ""
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock).use { recorder ->
            recorder.bindScope(SCOPE_A)
            recorder.record(event(), SCOPE_A)
            oldProcess = recorder.snapshot().events.single().processId
        }
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock).use { recorder ->
            recorder.bindScope(SCOPE_A)
            recorder.record(event(), SCOPE_A)
            val events = recorder.snapshot().events
            assertEquals(2, events.size)
            assertEquals(oldProcess, events.first().processId)
            assertNotEquals(oldProcess, events.last().processId)
            assertEquals(1L, events.last().sequence)
        }
    }

    @Test fun `queued work cannot cross a scope switch or an A B A transition`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val directory = temporary.newFolder()
        RollingLogStore(directory, clock).apply { bind(SCOPE_A); append(stamped()) }
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock, 8) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            RollingLogStore(directory, clock)
        }.use { recorder ->
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            recorder.bindScope(SCOPE_A)
            val oldA = recorder.captureScope()
            recorder.record(event(), SCOPE_A)
            recorder.bindScope(SCOPE_B)
            recorder.record(event(), SCOPE_B)
            recorder.bindScope(SCOPE_A)
            recorder.recordCaptured(event("background"), oldA)
            recorder.record(event("app_start"), SCOPE_A)
            release.countDown()
            assertEquals(listOf("app_start"), recorder.snapshot().events.map { it.kind })
        }
    }

    @Test fun `full queue drops events without blocking callback thread`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val directory = temporary.newFolder()
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock, 4) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            RollingLogStore(directory, clock)
        }.use { recorder ->
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            recorder.bindScope(SCOPE_A)
            val start = System.nanoTime()
            repeat(1000) { recorder.record(event(), SCOPE_A) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
            assertTrue(recorder.snapshot().truncated)
            release.countDown()
            var snapshot = recorder.snapshot()
            repeat(20) {
                if (snapshot.events.isEmpty()) { Thread.sleep(10); snapshot = recorder.snapshot() }
            }
            assertTrue(snapshot.truncated)
            assertTrue(snapshot.events.size <= 4)
            assertTrue(snapshot.events.isNotEmpty())
        }
    }

    @Test fun `storage failures do not escape or kill future recording`() {
        val directory = temporary.newFolder()
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock, storeFactory = {
            if (fail.get()) throw java.io.IOException("credential that must never be logged")
            RollingLogStore(directory, clock)
        }).use { recorder ->
            recorder.bindScope(SCOPE_A)
            recorder.record(event(), SCOPE_A)
            assertTrue(recorder.snapshot().truncated)
            fail.set(false)
            recorder.record(event(), SCOPE_A)
            val result = recorder.snapshot()
            assertEquals(1, result.events.size)
            assertTrue(result.truncated)
        }
    }

    @Test fun `real constructor repairs corrupt scope metadata for recorder and clear`() {
        val directory = temporary.newFolder()
        RollingLogStore(directory, clock).apply { bind(SCOPE_A); append(stamped(99)) }
        java.io.File(directory, "scope.json").writeText("{broken")
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock).use { recorder ->
            recorder.bindScope(SCOPE_B)
            recorder.record(event("app_start"), SCOPE_B)
            val result = recorder.snapshot()
            assertEquals(listOf("app_start"), result.events.map { it.kind })
            assertTrue(result.truncated)
            assertFalse(result.events.any { it.processId == PROCESS })
            recorder.clear()
            assertTrue(recorder.snapshot().events.isEmpty())
            recorder.record(event("foreground"), SCOPE_B)
            assertEquals(listOf("foreground"), recorder.snapshot().events.map { it.kind })
        }
    }

    @Test fun `status summaries record changes and a sixty second heartbeat`() {
        recorder().use { recorder ->
            recorder.bindScope(SCOPE_A)
            val status = DiagnosticEvent("status_received", "phone_app", connected = true,
                mode = "disarmed", openZones = 1, bypassedZones = 0)
            repeat(10) { recorder.record(status, SCOPE_A) }
            assertEquals(1, recorder.snapshot().events.size)
            clock.advance(59_999)
            recorder.record(status, SCOPE_A)
            assertEquals(1, recorder.snapshot().events.size)
            clock.advance(1)
            recorder.record(status, SCOPE_A)
            recorder.record(status.copy(openZones = 2), SCOPE_A)
            assertEquals(3, recorder.snapshot().events.size)
        }
    }

    @Test fun `logout makes snapshots empty and clears persisted data`() {
        val directory = temporary.newFolder()
        DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock).use { recorder ->
            recorder.bindScope(SCOPE_A)
            recorder.record(event(), SCOPE_A)
            assertEquals(1, recorder.snapshot().events.size)
            recorder.bindScope(null)
            assertNull(recorder.currentScope)
            assertTrue(recorder.snapshot().events.isEmpty())
            recorder.bindScope(SCOPE_A)
            assertTrue(recorder.snapshot().events.isEmpty())
        }
        assertFalse(directory.walk().filter { it.isFile }.any { it.readText().contains("foreground") })
    }

    @Test fun `export waiting on old queued work cannot return another session`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val directory = temporary.newFolder()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            DiagnosticRecorder(directory, "phone", "1.2.3", 1, clock, 8) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                RollingLogStore(directory, clock)
            }.use { recorder ->
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                recorder.bindScope(SCOPE_A)
                recorder.record(event(), SCOPE_A)
                val old = pool.submit<DeviceLog> { recorder.snapshot() }
                // The worker remains blocked, so an export cannot finish before the switch.
                Thread.sleep(50)
                recorder.bindScope(SCOPE_B)
                recorder.record(event("background"), SCOPE_B)
                release.countDown()
                assertTrue(old.get(2, TimeUnit.SECONDS).events.isEmpty())
                assertEquals(listOf("background"), recorder.snapshot().events.map { it.kind })
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `export budget cannot be smaller than the bounded envelope`() {
        recorder().use { recorder ->
            assertThrows(IllegalArgumentException::class.java) { recorder.snapshot(255) }
            recorder.bindScope(SCOPE_A)
            recorder.record(event(), SCOPE_A)
            val report = recorder.snapshot(256)
            assertTrue(kotlinx.serialization.json.Json.encodeToString(DeviceLog.serializer(), report).toByteArray().size <= 256)
        }
    }
}
