package com.remoteparadox.app

import com.remoteparadox.app.data.ActionResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionResultTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes full ActionResult with all fields`() {
        val raw = """{"success":true,"action":"check_update","message":"done"}"""
        val result = json.decodeFromString<ActionResult>(raw)
        assertTrue(result.success)
        assertEquals("check_update", result.action)
        assertEquals("done", result.message)
    }

    @Test
    fun `deserializes ActionResult missing action field`() {
        val raw = """{"success":true,"output":"up to date"}"""
        val result = json.decodeFromString<ActionResult>(raw)
        assertTrue(result.success)
        assertEquals("", result.action)
    }

    @Test
    fun `deserializes ActionResult missing both action and message`() {
        val raw = """{"success":true}"""
        val result = json.decodeFromString<ActionResult>(raw)
        assertTrue(result.success)
        assertEquals("", result.action)
        assertEquals("", result.message)
    }
}
