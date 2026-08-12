package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AACPManagerOwnershipStateTest {
    @Test
    fun outgoingOwnershipRequestDoesNotPretendAirPodsConfirmedIt() {
        assertFalse(
            AACPManager.shouldApplyControlStatusImmediately(
                ControlCommandIdentifiers.OWNS_CONNECTION,
                confirmedByAirPods = false
            )
        )
    }

    @Test
    fun receivedOwnershipAndOtherOutgoingControlsStillUpdateState() {
        assertTrue(
            AACPManager.shouldApplyControlStatusImmediately(
                ControlCommandIdentifiers.OWNS_CONNECTION,
                confirmedByAirPods = true
            )
        )
        assertTrue(
            AACPManager.shouldApplyControlStatusImmediately(
                ControlCommandIdentifiers.LISTENING_MODE,
                confirmedByAirPods = false
            )
        )
    }
}
