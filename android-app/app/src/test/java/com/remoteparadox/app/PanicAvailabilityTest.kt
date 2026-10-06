package com.remoteparadox.app

import com.remoteparadox.app.data.panicCommandAvailable
import com.remoteparadox.app.ui.screens.panicButtonsEnabled
import org.junit.Assert.*
import org.junit.Test

class PanicAvailabilityTest {
    @Test
    fun `unknown panel state does not remove HTTP panic availability`() {
        val state =
            AppState(
                alarmStatus = null,
                panicAvailable = panicCommandAvailable(true, true, true, false, false),
            )
        assertNull(state.alarmStatus)
        assertTrue(panicButtonsEnabled(state.panicAvailable, state.actionInProgress))
        assertTrue(panicButtonsEnabled(state.copy(wsConnected = false).panicAvailable, null))
    }

    @Test
    fun `BLE panic remains available when HTTP and panel telemetry are unavailable`() {
        val available = panicCommandAvailable(true, true, false, true, false)
        assertTrue(panicButtonsEnabled(available, null))
    }

    @Test
    fun `missing credentials or all transports unavailable disables panic`() {
        assertFalse(
            panicButtonsEnabled(panicCommandAvailable(false, true, true, true, false), null)
        )
        assertFalse(
            panicButtonsEnabled(panicCommandAvailable(true, false, true, false, false), null)
        )
        assertFalse(
            panicButtonsEnabled(panicCommandAvailable(true, true, false, false, false), null)
        )
    }

    @Test
    fun `busy lease survives cleared UI action and still disables panic`() {
        assertFalse(panicButtonsEnabled(panicCommandAvailable(true, true, true, true, true), null))
        assertFalse(panicButtonsEnabled(true, "disarm"))
        assertTrue(panicButtonsEnabled(panicCommandAvailable(true, true, true, true, false), null))
    }
}
