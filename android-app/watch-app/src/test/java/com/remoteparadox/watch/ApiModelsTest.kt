package com.remoteparadox.watch

import com.remoteparadox.watch.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ApiModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `AlarmStatus deserializes single partition`() {
        val raw = """
        {
          "partitions": [{
            "id": 1, "name": "Area 1", "armed": false,
            "mode": "disarmed", "ready": true,
            "zones": [
              {"id": 1, "name": "Front Door", "open": false, "partition_id": 1, "alarm": false}
            ]
          }],
          "connected": true
        }
        """.trimIndent()
        val status = json.decodeFromString<AlarmStatus>(raw)
        assertEquals(1, status.partitions.size)
        assertEquals("disarmed", status.partitions[0].mode)
        assertFalse(status.partitions[0].armed)
        assertTrue(status.connected)
        assertEquals("Front Door", status.partitions[0].zones[0].name)
    }

    @Test
    fun `AlarmStatus deserializes two partitions`() {
        val raw = """
        {
          "partitions": [
            {"id": 1, "name": "House", "armed": true, "mode": "armed_away", "zones": []},
            {"id": 2, "name": "Garage", "armed": false, "mode": "disarmed", "zones": []}
          ],
          "connected": true
        }
        """.trimIndent()
        val status = json.decodeFromString<AlarmStatus>(raw)
        assertEquals(2, status.partitions.size)
        assertTrue(status.partitions[0].armed)
        assertEquals("armed_away", status.partitions[0].mode)
        assertFalse(status.partitions[1].armed)
    }

    @Test
    fun `AlarmStatus with triggered zone`() {
        val raw = """
        {
          "partitions": [{
            "id": 1, "name": "Area 1", "armed": true,
            "mode": "triggered",
            "zones": [
              {"id": 1, "name": "Back Window", "open": true, "alarm": true, "partition_id": 1}
            ]
          }],
          "connected": true
        }
        """.trimIndent()
        val status = json.decodeFromString<AlarmStatus>(raw)
        assertEquals("triggered", status.partitions[0].mode)
        assertTrue(status.partitions[0].zones[0].alarm)
        assertEquals("Back Window", status.partitions[0].zones[0].name)
    }

    @Test
    fun `ArmRequest serializes with partition id`() {
        val req = ArmRequest(code = "1234", partitionId = 2)
        val serialized = json.encodeToString(ArmRequest.serializer(), req)
        assertTrue(serialized.contains("\"partition_id\":2"))
        assertTrue(serialized.contains("\"code\":\"1234\""))
    }

    @Test
    fun `LoginRequest serializes correctly`() {
        val req = LoginRequest(username = "admin", password = "secret")
        val serialized = json.encodeToString(LoginRequest.serializer(), req)
        assertTrue(serialized.contains("\"username\":\"admin\""))
        assertTrue(serialized.contains("\"password\":\"secret\""))
    }

    @Test
    fun `LoginResponse deserializes correctly`() {
        val raw = """{"token":"abc123","username":"admin","role":"admin"}"""
        val resp = json.decodeFromString<LoginResponse>(raw)
        assertEquals("abc123", resp.token)
        assertEquals("admin", resp.username)
        assertEquals("admin", resp.role)
    }

    @Test
    fun `ActionResult deserializes success`() {
        val raw = """{"success":true,"action":"arm_away","message":"OK"}"""
        val result = json.decodeFromString<ActionResult>(raw)
        assertTrue(result.success)
        assertEquals("arm_away", result.action)
    }

    @Test
    fun `PanicRequest defaults to emergency`() {
        val req = PanicRequest(partitionId = 1)
        assertEquals("emergency", req.panicType)
    }

    @Test
    fun `AlarmStatus ignores unknown fields`() {
        val raw = """
        {
          "partitions": [{
            "id": 1, "name": "Area 1", "armed": false,
            "mode": "disarmed", "zones": [],
            "some_future_field": true
          }],
          "connected": true,
          "another_unknown": 42
        }
        """.trimIndent()
        val status = json.decodeFromString<AlarmStatus>(raw)
        assertEquals(1, status.partitions.size)
    }

    @Test
    fun `PartitionInfo entry_delay defaults to false`() {
        val raw = """{"id":1,"name":"Area 1","armed":false,"mode":"disarmed","zones":[]}"""
        val p = json.decodeFromString<PartitionInfo>(raw)
        assertFalse(p.entryDelay)
    }

    @Test
    fun `BypassRequest serializes with zone_id and bypass true`() {
        val req = BypassRequest(zoneId = 3, bypass = true)
        val serialized = json.encodeToString(BypassRequest.serializer(), req)
        assertTrue(serialized.contains("\"zone_id\":3"))
        assertTrue(serialized.contains("\"bypass\":true"))
    }

    @Test
    fun `BypassRequest serializes with bypass false for unbypass`() {
        val req = BypassRequest(zoneId = 5, bypass = false)
        val serialized = json.encodeToString(BypassRequest.serializer(), req)
        assertTrue(serialized.contains("\"zone_id\":5"))
        assertTrue(serialized.contains("\"bypass\":false"))
    }

    @Test
    fun `ZoneInfo deserializes with bypassed field`() {
        val raw = """{"id":2,"name":"Kitchen","open":true,"bypassed":true,"partition_id":1,"alarm":false}"""
        val zone = json.decodeFromString<ZoneInfo>(raw)
        assertTrue(zone.bypassed)
        assertTrue(zone.open)
        assertEquals("Kitchen", zone.name)
    }

    @Test
    fun `ZoneInfo bypassed defaults to false`() {
        val raw = """{"id":1,"name":"Door","open":false,"partition_id":1,"alarm":false}"""
        val zone = json.decodeFromString<ZoneInfo>(raw)
        assertFalse(zone.bypassed)
    }
}
