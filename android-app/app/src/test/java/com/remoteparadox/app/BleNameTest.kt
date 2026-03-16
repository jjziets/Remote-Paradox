package com.remoteparadox.app

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BleNameTest {

    companion object {
        private const val TARGET_NAME = "Remote Paradox"

        fun isTargetDevice(name: String?): Boolean {
            return name?.contains(TARGET_NAME, ignoreCase = true) == true
        }
    }

    @Test
    fun `matches exact device name`() {
        assertTrue(isTargetDevice("Remote Paradox"))
    }

    @Test
    fun `matches case insensitive`() {
        assertTrue(isTargetDevice("remote paradox"))
    }

    @Test
    fun `matches with suffix`() {
        assertTrue(isTargetDevice("Remote Paradox-1"))
    }

    @Test
    fun `does not match old underscore name`() {
        assertFalse(isTargetDevice("Remote_Paradox"))
    }

    @Test
    fun `rejects null name`() {
        assertFalse(isTargetDevice(null))
    }

    @Test
    fun `rejects unrelated device`() {
        assertFalse(isTargetDevice("My Speaker"))
    }

    @Test
    fun `target name has no underscore`() {
        assertFalse(TARGET_NAME.contains("_"))
    }
}
