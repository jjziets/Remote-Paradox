package com.remoteparadox.app

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BleNameTest {

    companion object {
        private const val TARGET_NAME = "Remote Paradox"
    }

    @Test
    fun `matches exact device name`() {
        assertTrue("Remote Paradox".contains(TARGET_NAME, ignoreCase = true))
    }

    @Test
    fun `matches case insensitive`() {
        assertTrue("remote paradox".contains(TARGET_NAME, ignoreCase = true))
    }

    @Test
    fun `does not match old underscore name`() {
        assertFalse("Remote_Paradox" == TARGET_NAME)
    }

    @Test
    fun `target name has no underscore`() {
        assertFalse(TARGET_NAME.contains("_"))
    }
}
