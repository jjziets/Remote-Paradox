package com.remoteparadox.diagnostics

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DiagnosticCodecTest {
    private val json = DiagnosticCodec.json

    @Test fun `fixture is actual client serialization and round trips`() {
        val fixture = javaClass.getResource("/client-report-v1.json")!!.readText()
        assertEquals(json.encodeToString(fixtureReport()) + "\n", fixture)
        assertEquals(fixtureReport(), json.decodeFromString<DiagnosticReport>(fixture))
    }

    @Test fun `defaults are encoded and optional nulls omitted`() {
        val raw = json.encodeToString(fixtureReport().copy(watchStatus = "unavailable", watch = null))
        assertTrue(raw.contains("\"schemaVersion\":1"))
        assertTrue(raw.contains("\"truncated\":false"))
        assertFalse(raw.contains("\"watch\":"))
        assertFalse(raw.contains("\"error\":"))
    }

    @Test fun `unknown fields rejected at every level`() {
        val event = json.encodeToString(stamped())
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>(event.dropLast(1) + ",\"message\":\"secret\"}") }
        val report = json.encodeToString(fixtureReport())
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticReport>(report.dropLast(1) + ",\"token\":\"secret\"}") }
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticReport>(report.replace("\"device\":\"phone\"", "\"device\":\"phone\",\"label\":\"secret\"")) }
    }

    @Test fun `wire stamps cannot be missing or placeholders`() {
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>("""{"kind":"foreground","source":"phone_app"}""") }
        assertThrows(Exception::class.java) { json.encodeToString(DiagnosticEvent("foreground", "phone_app")) }
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>(json.encodeToString(stamped()).replace("\"sequence\":1", "\"sequence\":0")) }
    }

    @Test fun `all free form enum and identifier substitutions are rejected`() {
        val invalid = listOf(stamped().copy(kind = "secret"), stamped().copy(source = "secret"),
            stamped().copy(route = "https://secret/alarm/status?token=secret"), stamped().copy(mode = "kitchen"),
            stamped().copy(error = "password=secret"), stamped().copy(requestId = "token"))
        invalid.forEach { event -> assertThrows(Exception::class.java) { json.encodeToString(event) } }
    }

    @Test fun `numeric bounds and JSON types are strict`() {
        val invalid = listOf(stamped().copy(partitionId = 0), stamped().copy(partitionId = 33),
            stamped().copy(zoneId = 513), stamped().copy(zoneId = 0), stamped().copy(httpStatus = 600),
            stamped().copy(httpStatus = 99), stamped().copy(elapsedMs = -1), stamped().copy(openZones = -1),
            stamped().copy(bypassedZones = 513), stamped().copy(timeMs = -1), stamped().copy(monotonicMs = -1))
        invalid.forEach { event -> assertThrows(Exception::class.java) { json.encodeToString(event) } }
        val event = json.encodeToString(stamped())
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>(event.replace("\"success\":true", "\"success\":\"true\"")) }
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>(event.replace("\"elapsedMs\":42", "\"elapsedMs\":1.5")) }
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>(event.replace("\"elapsedMs\":42", "\"elapsedMs\":\"42\"")) }
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticEvent>(event.replace("\"httpStatus\":200", "\"httpStatus\":true")) }
        val report = json.encodeToString(fixtureReport())
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticReport>(report.replace("\"buildCode\":123", "\"buildCode\":\"123\"")) }
        assertThrows(Exception::class.java) { json.decodeFromString<DiagnosticReport>(report.replace("\"truncated\":false", "\"truncated\":\"false\"")) }
    }

    @Test fun `report and watch consistency are enforced`() {
        assertThrows(Exception::class.java) { fixtureReport().copy(watch = null) }
        assertThrows(Exception::class.java) { fixtureReport().copy(watchStatus = "unavailable") }
        assertThrows(Exception::class.java) { fixtureReport().copy(schemaVersion = 2) }
        assertThrows(Exception::class.java) { fixtureReport().copy(phone = fixtureReport().watch!!) }
        assertThrows(Exception::class.java) { WatchLogReply(REPORT, SCOPE_A, "included") }
        assertThrows(Exception::class.java) { WatchLogReply(REPORT, SCOPE_A, "scope_mismatch", fixtureReport().watch) }
    }

    @Test fun `wear messages fit transport limits`() {
        assertTrue(json.encodeToString(WatchLogRequest(REPORT, SCOPE_A)).toByteArray().size <= 256)
        assertTrue(json.encodeToString(WatchLogReply(REPORT, SCOPE_A, "included", fixtureReport().watch)).toByteArray().size <= 64 * 1024)
    }

    @Test fun `scope canonicalizes origin and path without changing username`() {
        val expected = MessageDigest.getInstance("SHA-256").digest("https://example.com:443/pi\nAlice".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, Diagnostics.scope("HTTPS://EXAMPLE.COM/pi/", "Alice"))
        assertEquals(expected, Diagnostics.scope("https://example.com:443/pi", "Alice"))
        assertNotEquals(expected, Diagnostics.scope("https://example.com/pi", "alice"))
        assertNotEquals(expected, Diagnostics.scope("https://example.com:444/pi", "Alice"))
        assertEquals(Diagnostics.scope("http://[::1]/", "a"), Diagnostics.scope("http://[::1]:80", "a"))
    }

    @Test fun `invalid and credential bearing scope URLs are rejected`() {
        assertNull(Diagnostics.scope(null, "a"))
        assertNull(Diagnostics.scope("https://host", null))
        assertNull(Diagnostics.scope("file:///secret", "a"))
        assertNull(Diagnostics.scope("https://user:password@host", "a"))
        assertNull(Diagnostics.scope("https://host?token=secret", "a"))
        assertNull(Diagnostics.scope("https://host#secret", "a"))
    }
}
