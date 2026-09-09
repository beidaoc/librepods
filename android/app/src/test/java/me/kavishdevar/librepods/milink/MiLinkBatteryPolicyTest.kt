package me.kavishdevar.librepods.milink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiLinkBatteryPolicyTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun `persisted connected state cannot replace native battery before live reply`() {
        assertFalse(MiLinkBatteryPolicy.owns(false, true, address, address))
    }

    @Test fun `reopening card for live device can replace native unknown battery`() {
        repeat(5) { assertTrue(MiLinkBatteryPolicy.owns(true, true, address, address.lowercase())) }
    }

    @Test fun `disconnect releases native query even when last address remains cached`() {
        assertFalse(MiLinkBatteryPolicy.owns(true, false, address, address))
    }

    @Test fun `switching headset cannot reuse previous headset battery`() {
        assertFalse(MiLinkBatteryPolicy.owns(true, true, address, "00:11:22:33:44:55"))
    }

    @Test fun `unidentified device never receives a battery override`() {
        listOf(null, "", " ").forEach {
            assertFalse(MiLinkBatteryPolicy.owns(true, true, address, it))
        }
        assertFalse(MiLinkBatteryPolicy.owns(true, true, "", address))
    }
}
