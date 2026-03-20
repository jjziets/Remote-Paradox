package com.remoteparadox.watch

import com.remoteparadox.watch.data.AlarmStatus
import com.remoteparadox.watch.data.PartitionInfo
import com.remoteparadox.watch.data.ZoneInfo
import org.junit.Assert.*
import org.junit.Test

class WatchViewModelTest {

    @Test
    fun `partitionDisplayColor returns green for disarmed`() {
        assertEquals(PartitionColor.GREEN, partitionDisplayColor("disarmed"))
    }

    @Test
    fun `partitionDisplayColor returns red for armed_away`() {
        assertEquals(PartitionColor.RED, partitionDisplayColor("armed_away"))
    }

    @Test
    fun `partitionDisplayColor returns red for armed_home`() {
        assertEquals(PartitionColor.RED, partitionDisplayColor("armed_home"))
    }

    @Test
    fun `partitionDisplayColor returns amber for arming`() {
        assertEquals(PartitionColor.AMBER, partitionDisplayColor("arming"))
    }

    @Test
    fun `partitionDisplayColor returns alarm_red for triggered`() {
        assertEquals(PartitionColor.ALARM_RED, partitionDisplayColor("triggered"))
    }

    @Test
    fun `partitionDisplayColor returns amber for entry_delay`() {
        assertEquals(PartitionColor.AMBER, partitionDisplayColor("entry_delay"))
    }

    @Test
    fun `partitionDisplayColor returns amber for exit_delay`() {
        assertEquals(PartitionColor.AMBER, partitionDisplayColor("exit_delay"))
    }

    @Test
    fun `triggeredZoneNames returns alarm zone names`() {
        val partition = PartitionInfo(
            id = 1, name = "House", armed = true, mode = "triggered",
            zones = listOf(
                ZoneInfo(id = 1, name = "Front Door", open = true, alarm = true),
                ZoneInfo(id = 2, name = "Kitchen", open = false, alarm = false),
                ZoneInfo(id = 3, name = "Back Window", open = true, alarm = true),
            ),
        )
        val names = triggeredZoneNames(partition)
        assertEquals(listOf("Front Door", "Back Window"), names)
    }

    @Test
    fun `triggeredZoneNames includes wasInAlarm zones`() {
        val partition = PartitionInfo(
            id = 1, name = "House", armed = true, mode = "triggered",
            zones = listOf(
                ZoneInfo(id = 1, name = "Garage", open = false, alarm = false, wasInAlarm = true),
            ),
        )
        val names = triggeredZoneNames(partition)
        assertEquals(listOf("Garage"), names)
    }

    @Test
    fun `triggeredZoneNames empty when no alarms`() {
        val partition = PartitionInfo(
            id = 1, name = "House", armed = true, mode = "armed_away",
            zones = listOf(
                ZoneInfo(id = 1, name = "Front Door", open = false, alarm = false),
            ),
        )
        val names = triggeredZoneNames(partition)
        assertTrue(names.isEmpty())
    }

    @Test
    fun `statusLabel returns human-readable text`() {
        assertEquals("Disarmed", statusLabel("disarmed"))
        assertEquals("Armed Away", statusLabel("armed_away"))
        assertEquals("Armed Home", statusLabel("armed_home"))
        assertEquals("Arming", statusLabel("arming"))
        assertEquals("TRIGGERED", statusLabel("triggered"))
        assertEquals("Entry Delay", statusLabel("entry_delay"))
        assertEquals("Exit Delay", statusLabel("exit_delay"))
        assertEquals("unknown_mode", statusLabel("unknown_mode"))
    }

    @Test
    fun `isSplitScreen true for two partitions`() {
        val status = AlarmStatus(
            partitions = listOf(
                PartitionInfo(id = 1, name = "House", armed = false, mode = "disarmed"),
                PartitionInfo(id = 2, name = "Garage", armed = false, mode = "disarmed"),
            ),
            connected = true,
        )
        assertTrue(isSplitScreen(status))
    }

    @Test
    fun `isSplitScreen false for one partition`() {
        val status = AlarmStatus(
            partitions = listOf(
                PartitionInfo(id = 1, name = "House", armed = false, mode = "disarmed"),
            ),
            connected = true,
        )
        assertFalse(isSplitScreen(status))
    }

    @Test
    fun `openUnbypassedZones returns only open and not bypassed zones`() {
        val partition = PartitionInfo(
            id = 1, name = "House", armed = false, mode = "disarmed",
            zones = listOf(
                ZoneInfo(id = 1, name = "Front Door", open = true, bypassed = false),
                ZoneInfo(id = 2, name = "Kitchen", open = false, bypassed = false),
                ZoneInfo(id = 3, name = "Back Window", open = true, bypassed = true),
                ZoneInfo(id = 4, name = "Garage", open = true, bypassed = false),
            ),
        )
        val result = openUnbypassedZones(partition)
        assertEquals(2, result.size)
        assertEquals("Front Door", result[0].name)
        assertEquals("Garage", result[1].name)
    }

    @Test
    fun `openUnbypassedZones empty when all zones closed`() {
        val partition = PartitionInfo(
            id = 1, name = "House", armed = false, mode = "disarmed",
            zones = listOf(
                ZoneInfo(id = 1, name = "Front Door", open = false, bypassed = false),
            ),
        )
        assertTrue(openUnbypassedZones(partition).isEmpty())
    }

    @Test
    fun `WATCH_VERSION_QUERY_PATH is correct constant`() {
        assertEquals("/paradox/watch-version-query", WATCH_VERSION_QUERY_PATH)
    }

    @Test
    fun `WATCH_VERSION_REPLY_PATH is correct constant`() {
        assertEquals("/paradox/watch-version-reply", WATCH_VERSION_REPLY_PATH)
    }

    @Test
    fun `WATCH_UPDATE_CHANNEL_PATH is correct constant`() {
        assertEquals("/paradox/watch-update-apk", WATCH_UPDATE_CHANNEL_PATH)
    }

    @Test
    fun `smartAction returns DISARM when armed`() {
        assertEquals(SmartAction.DISARM, smartAction("armed_away", armed = true, armAwayEnabled = true, armStayEnabled = true))
    }

    @Test
    fun `smartAction returns DISARM when triggered`() {
        assertEquals(SmartAction.DISARM, smartAction("triggered", armed = true, armAwayEnabled = true, armStayEnabled = true))
    }

    @Test
    fun `smartAction returns DISARM when arming`() {
        assertEquals(SmartAction.DISARM, smartAction("arming", armed = false, armAwayEnabled = true, armStayEnabled = true))
    }

    @Test
    fun `smartAction returns DISARM when exit_delay`() {
        assertEquals(SmartAction.DISARM, smartAction("exit_delay", armed = false, armAwayEnabled = true, armStayEnabled = true))
    }

    @Test
    fun `smartAction returns SHOW_DIALOG when both modes enabled and disarmed`() {
        assertEquals(SmartAction.SHOW_DIALOG, smartAction("disarmed", armed = false, armAwayEnabled = true, armStayEnabled = true))
    }

    @Test
    fun `smartAction returns ARM_AWAY when only away enabled and disarmed`() {
        assertEquals(SmartAction.ARM_AWAY, smartAction("disarmed", armed = false, armAwayEnabled = true, armStayEnabled = false))
    }

    @Test
    fun `smartAction returns ARM_STAY when only stay enabled and disarmed`() {
        assertEquals(SmartAction.ARM_STAY, smartAction("disarmed", armed = false, armAwayEnabled = false, armStayEnabled = true))
    }

    @Test
    fun `bypassedZones returns only bypassed zones`() {
        val partition = PartitionInfo(
            id = 1, name = "House", armed = false, mode = "disarmed",
            zones = listOf(
                ZoneInfo(id = 1, name = "Front Door", open = false, bypassed = true),
                ZoneInfo(id = 2, name = "Kitchen", open = false, bypassed = false),
                ZoneInfo(id = 3, name = "Back Window", open = true, bypassed = true),
            ),
        )
        val result = bypassedZones(partition)
        assertEquals(2, result.size)
        assertEquals("Front Door", result[0].name)
        assertEquals("Back Window", result[1].name)
    }
}
