package com.remoteparadox.watch

import com.remoteparadox.watch.data.StatusCachePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StatusCachePolicyTest {
    private val owner = "https://alarm/|alice|certificate"

    @Test fun `old HTTP and websocket tickets cannot restore invalidated status`() = runTest {
        val policy = StatusCachePolicy()
        val oldRead = policy.capture(owner)
        val oldSocket = policy.capture(owner)
        var cached = "disarmed"
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val read = async {
            requestStarted.complete(Unit)
            releaseResponse.await()
            if (policy.current(oldRead, owner)) cached = "disarmed"
        }
        requestStarted.await()
        policy.command(oldRead, { owner }, { cached = "unavailable" }) {
            assertEquals("unavailable", cached)
            assertFalse(policy.current(oldSocket, owner))
            assertFalse(policy.current(policy.capture(owner), owner))
        }
        releaseResponse.complete(Unit)
        read.await()
        assertEquals("unavailable", cached)
        assertFalse(policy.current(oldSocket, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `reads started during a command remain rejected after its response`() = runTest {
        val policy = StatusCachePolicy()
        val during = policy.command(policy.capture(owner), { owner }, {}) { policy.capture(owner) }!!
        assertFalse(policy.current(during, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `lost response never replays and allows only new status reads`() = runTest {
        val policy = StatusCachePolicy()
        val before = policy.capture(owner)
        var sends = 0
        try {
            policy.command(before, { owner }, {}) { sends++; throw IOException("Response lost") }
            fail("Expected connection failure")
        } catch (_: IOException) { }
        assertEquals(1, sends)
        assertFalse(policy.current(before, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `cancellation releases the command without accepting old results`() = runTest {
        val policy = StatusCachePolicy()
        val before = policy.capture(owner)
        try {
            policy.command(before, { owner }, {}) { throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(policy.current(before, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `failed durable invalidation prevents dispatch and releases guard`() = runTest {
        val policy = StatusCachePolicy()
        var sent = false
        try {
            policy.command(policy.capture(owner), { owner }, { throw IOException("Disk failure") }) { sent = true }
            fail("Expected disk failure")
        } catch (_: IOException) { }
        assertFalse(sent)
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `owner changes reject results even without an explicit clear`() {
        val policy = StatusCachePolicy()
        val ticket = policy.capture(owner)
        assertFalse(policy.current(ticket, "https://other-alarm/|alice|certificate"))
        assertFalse(policy.sameSession(ticket, "https://alarm/|bob|certificate"))
        assertFalse(policy.sameSession(ticket, "https://alarm/|alice|new-certificate"))
    }

    @Test fun `same owner resync or logout and login rejects prior session results`() {
        val policy = StatusCachePolicy()
        val oldSession = policy.capture(owner)
        policy.newSession()
        assertFalse(policy.sameSession(oldSession, owner))
        assertFalse(policy.current(oldSession, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `old command completion cannot release a new session command`() = runTest {
        val policy = StatusCachePolicy()
        var replacementCommand = policy.capture(owner)
        policy.command(policy.capture(owner), { owner }, {}) {
            policy.newSession()
            replacementCommand = policy.beginCommand(policy.capture(owner), owner)!!
        }
        assertFalse(policy.current(policy.capture(owner), owner))
        assertTrue(policy.finishCommand(replacementCommand, owner))
        assertTrue(policy.current(policy.capture(owner), owner))
    }

    @Test fun `a concurrent command cannot dispatch or release the first command`() = runTest {
        val policy = StatusCachePolicy()
        policy.command(policy.capture(owner), { owner }, {}) {
            assertNull(policy.command(policy.capture(owner), { owner }, { fail("Must not invalidate") }) {
                fail("Must not send")
            })
            assertFalse(policy.current(policy.capture(owner), owner))
        }
        assertTrue(policy.current(policy.capture(owner), owner))
    }
}
