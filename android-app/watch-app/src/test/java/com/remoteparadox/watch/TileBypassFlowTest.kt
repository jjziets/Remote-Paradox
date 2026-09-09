package com.remoteparadox.watch

import com.remoteparadox.watch.data.ZoneInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileBypassFlowTest {
    private val pickerDismissed = WatchState(
        screen = WatchScreen.Dashboard,
        tileActionDone = true,
    )
    private val openZones = listOf(ZoneInfo(id = 7, name = "Open zone", open = true))

    @Test
    fun `arm choice from tile keeps bypass confirmation open for either mode`() {
        for (action in listOf("arm_away", "arm_stay")) {
            val pending = PendingArm(partitionId = 2, action = action, openZones = openZones)
            assertFalse(pickerDismissed.copy(pendingArm = pending).canReturnToTile)
        }
    }

    @Test
    fun `single enabled arming mode still waits for bypass confirmation`() {
        val pending = PendingArm(2, "arm_stay", openZones)
        assertFalse(pickerDismissed.copy(
            pendingArm = pending, armAwayEnabled = false, armStayEnabled = true,
        ).canReturnToTile)
        assertFalse(pickerDismissed.copy(
            pendingArm = pending.copy(action = "arm_away"),
            armAwayEnabled = true, armStayEnabled = false,
        ).canReturnToTile)
    }

    @Test
    fun `tile remains open through bypass and following arm command`() {
        val pending = PendingArm(2, "arm_away", openZones)
        val confirmation = pickerDismissed.copy(pendingArm = pending)
        val bypassing = confirmation.copy(actionInProgress = "bypass")
        val arming = bypassing.copy(pendingArm = null, actionInProgress = "arm_away")
        assertFalse(confirmation.canReturnToTile)
        assertFalse(bypassing.canReturnToTile)
        assertFalse(arming.canReturnToTile)
        assertTrue(arming.copy(actionInProgress = null).canReturnToTile)
    }

    @Test
    fun `dismissing bypass confirmation permits return without sending an action`() {
        val confirmation = pickerDismissed.copy(pendingArm = PendingArm(2, "arm_away", openZones))
        assertFalse(confirmation.canReturnToTile)
        assertTrue(confirmation.copy(pendingArm = null).canReturnToTile)
    }

    @Test
    fun `none of the in flight commands may close the activity`() {
        for (action in listOf("arm_away", "arm_stay", "disarm", "bypass")) {
            assertFalse(pickerDismissed.copy(actionInProgress = action).canReturnToTile)
        }
    }

    @Test
    fun `failed command or authentication screen does not count as completed tile flow`() {
        assertFalse(pickerDismissed.copy(error = "Bypass failed").canReturnToTile)
        assertFalse(pickerDismissed.copy(screen = WatchScreen.Setup).canReturnToTile)
    }

    @Test
    fun `normal app launch or a new tile request is not a completed tile flow`() {
        assertFalse(WatchState(screen = WatchScreen.Dashboard).canReturnToTile)
        assertFalse(pickerDismissed.copy(tilePartitionId = 2, tileActionDone = false).canReturnToTile)
    }

    @Test
    fun `picker cancellation can return to the tile`() {
        assertTrue(pickerDismissed.canReturnToTile)
    }
}
