package com.remoteparadox.app

import com.remoteparadox.app.data.MaintenanceJobResponse
import com.remoteparadox.app.data.MaintenanceLogResponse
import com.remoteparadox.app.data.MaintenanceStatusResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes maintenance status with current job`() {
        val raw = """
            {
              "active": true,
              "current_job": {
                "job_id": "job-123",
                "action": "check-updates",
                "status": "running",
                "message": "Checking",
                "created_at": "2026-06-01T10:00:00Z",
                "updated_at": "2026-06-01T10:00:01Z"
              },
              "updates_available": 4,
              "security_updates_available": 1,
              "reboot_required": true,
              "security_upgrade_supported": true
            }
        """.trimIndent()

        val status = json.decodeFromString<MaintenanceStatusResponse>(raw)

        assertTrue(status.active)
        assertEquals("job-123", status.currentJob?.jobId)
        assertEquals("check-updates", status.currentJob?.action)
        assertEquals(4, status.updatesAvailable)
        assertEquals(1, status.securityUpdatesAvailable)
        assertTrue(status.rebootRequired)
        assertEquals(true, status.securityUpgradeSupported)
    }

    @Test
    fun `deserializes maintenance job with optional fields omitted`() {
        val raw = """{"job_id":"job-456","status":"queued"}"""

        val job = json.decodeFromString<MaintenanceJobResponse>(raw)

        assertEquals("job-456", job.jobId)
        assertEquals("", job.action)
        assertEquals("queued", job.status)
        assertEquals(null, job.exitCode)
        assertFalse(job.rebootRequired)
    }

    @Test
    fun `deserializes maintenance job update metadata`() {
        val raw = """{"job_id":"job-457","status":"succeeded","updates_available":9,"reboot_required":true}"""

        val job = json.decodeFromString<MaintenanceJobResponse>(raw)

        assertEquals(9, job.updatesAvailable)
        assertTrue(job.rebootRequired)
    }

    @Test
    fun `deserializes maintenance log response lines`() {
        val raw = """{"job_id":"job-789","lines":["line one","line two"],"truncated":true}"""

        val log = json.decodeFromString<MaintenanceLogResponse>(raw)

        assertEquals("job-789", log.jobId)
        assertEquals(listOf("line one", "line two"), log.lines)
        assertTrue(log.truncated)
    }

    @Test
    fun `maintenance log helper keeps only tail lines`() {
        val raw = (1..12).joinToString("\n") { "line-$it" }

        val bounded = boundMaintenanceLog(raw, maxLines = 5, maxChars = 1_000)

        assertEquals((8..12).joinToString("\n") { "line-$it" }, bounded)
    }

    @Test
    fun `maintenance terminal status helper recognizes running and terminal states`() {
        assertTrue(isTerminalMaintenanceStatus("succeeded"))
        assertTrue(isTerminalMaintenanceStatus("unsupported"))
        assertFalse(isTerminalMaintenanceStatus("running"))
        assertFalse(isTerminalMaintenanceStatus("queued"))
    }
}
