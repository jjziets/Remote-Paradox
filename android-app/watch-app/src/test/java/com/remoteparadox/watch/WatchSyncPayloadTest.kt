package com.remoteparadox.watch

import com.remoteparadox.watch.data.WatchSyncPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WatchSyncPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `payload deserializes all fields`() {
        val raw = """
        {
          "host": "192.168.1.50",
          "port": 9433,
          "fingerprint": "abc123",
          "token": "jwt.token.here",
          "username": "admin",
          "alarmCode": "1234"
        }
        """.trimIndent()
        val payload = json.decodeFromString<WatchSyncPayload>(raw)
        assertEquals("192.168.1.50", payload.host)
        assertEquals(9433, payload.port)
        assertEquals("abc123", payload.fingerprint)
        assertEquals("jwt.token.here", payload.token)
        assertEquals("admin", payload.username)
        assertEquals("1234", payload.alarmCode)
    }

    @Test
    fun `payload serializes to json`() {
        val payload = WatchSyncPayload(
            host = "10.0.0.1",
            port = 8443,
            fingerprint = "",
            token = "mytoken",
            username = "user1",
            alarmCode = "5678",
        )
        val serialized = json.encodeToString(WatchSyncPayload.serializer(), payload)
        assertTrue(serialized.contains("\"host\":\"10.0.0.1\""))
        assertTrue(serialized.contains("\"port\":8443"))
        assertTrue(serialized.contains("\"token\":\"mytoken\""))
        assertTrue(serialized.contains("\"alarmCode\":\"5678\""))
    }

    @Test
    fun `payload ignores unknown fields`() {
        val raw = """
        {
          "host": "pi.local",
          "port": 9433,
          "fingerprint": "",
          "token": "tok",
          "username": "u",
          "alarmCode": "0",
          "futureField": true
        }
        """.trimIndent()
        val payload = json.decodeFromString<WatchSyncPayload>(raw)
        assertEquals("pi.local", payload.host)
    }

    @Test
    fun `payload roundtrip via bytes matches original`() {
        val original = WatchSyncPayload(
            host = "remote-paradox",
            port = 9433,
            fingerprint = "sha256hash",
            token = "long.jwt.token",
            username = "admin",
            alarmCode = "9999",
        )
        val bytes = json.encodeToString(WatchSyncPayload.serializer(), original).toByteArray(Charsets.UTF_8)
        val restored = json.decodeFromString<WatchSyncPayload>(String(bytes, Charsets.UTF_8))
        assertEquals(original, restored)
    }
}
