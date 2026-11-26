package io.github.gcbond.openhc.settings

import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.wireless.WirelessPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectReminderTest {

    private fun snapshot(vararg changed: Pair<PendingSetting, String>): Map<PendingSetting, String> {
        val base = PendingSetting.values().associateWith { "old" }
        return base + changed.toMap()
    }

    @Test
    fun noConnectionNoReminder() {
        // 没在连接：下一次连接自然按新设置，不提醒。
        val changed = ReconnectReminder.changed(
            snapshot(PendingSetting.FPS to "60"),
            snapshot(PendingSetting.FPS to "30"),
            wiredActive = false,
            wirelessActive = false,
        )
        assertTrue(changed.isEmpty())
    }

    @Test
    fun unchangedSettingsNoReminder() {
        val same = snapshot(PendingSetting.FPS to "60")
        assertTrue(ReconnectReminder.changed(same, same, wiredActive = true, wirelessActive = true).isEmpty())
    }

    @Test
    fun listsChangedInEnumOrder() {
        val before = snapshot(PendingSetting.FPS to "60", PendingSetting.CLARITY to "standard")
        val after = snapshot(PendingSetting.FPS to "30", PendingSetting.CLARITY to "high")
        assertEquals(
            listOf(PendingSetting.FPS, PendingSetting.CLARITY),
            ReconnectReminder.changed(before, after, wiredActive = true, wirelessActive = false),
        )
    }

    @Test
    fun wirelessOnlyItemsSkippedWhenOnlyWired() {
        // 只有有线在连时，只影响无线的项改了不用提醒（有线根本不读它）。
        val before = snapshot(PendingSetting.CAR_HOTSPOT_NAME to "a")
        val after = snapshot(PendingSetting.CAR_HOTSPOT_NAME to "b")
        assertTrue(ReconnectReminder.changed(before, after, wiredActive = true, wirelessActive = false).isEmpty())
        assertEquals(
            listOf(PendingSetting.CAR_HOTSPOT_NAME),
            ReconnectReminder.changed(before, after, wiredActive = false, wirelessActive = true),
        )
    }

    @Test
    fun replugRequiredDoesNotUseOldSettings() {
        // 要拔线重插的那一下会重新开始、重新读设置，不算「还在用旧设置」。
        assertFalse(ReconnectReminder.wiredUsesOldSettings(Phase.REPLUG_REQUIRED))
        assertFalse(ReconnectReminder.wiredUsesOldSettings(Phase.IDLE))
        assertFalse(ReconnectReminder.wiredUsesOldSettings(Phase.STOPPING))
        assertTrue(ReconnectReminder.wiredUsesOldSettings(Phase.PROJECTING))
        assertTrue(ReconnectReminder.wiredUsesOldSettings(Phase.WAITING_FOR_PHONE))
    }

    @Test
    fun wirelessStoppingDoesNotUseOldSettings() {
        assertFalse(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.IDLE))
        assertFalse(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.STOPPING))
        assertTrue(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.WAITING_FOR_SCAN))
        assertTrue(ReconnectReminder.wirelessUsesOldSettings(WirelessPhase.PROJECTING))
    }
}
