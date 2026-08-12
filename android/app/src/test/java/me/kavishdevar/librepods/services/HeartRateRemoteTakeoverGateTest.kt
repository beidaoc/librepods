package me.kavishdevar.librepods.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateRemoteTakeoverGateTest {
    @Test
    fun existingMacPlaybackIsIgnoredUntilMacPausesAndStartsAgain() {
        val gate = HeartRateRemoteTakeoverGate()

        gate.onHeartRateRequested()

        assertFalse(gate.onRemoteStreamingStateChanged(true))
        assertFalse(gate.onRemoteStreamingStateChanged(true))
        assertFalse(gate.onRemoteStreamingStateChanged(false))
        assertTrue(gate.onRemoteStreamingStateChanged(true))
    }

    @Test
    fun validatedHeartRateArmsTakeoverWhenMacDidNotReportItsPause() {
        val gate = HeartRateRemoteTakeoverGate()
        gate.onHeartRateRequested()
        assertFalse(gate.onRemoteStreamingStateChanged(true))

        gate.onHeartRateStreamingStarted()

        assertTrue(gate.onRemoteStreamingStateChanged(true))
    }

    @Test
    fun remotePlaybackReleasesOwnershipWhenHeartRateWasNotRequested() {
        val gate = HeartRateRemoteTakeoverGate()

        assertTrue(gate.onRemoteStreamingStateChanged(true))
    }

    @Test
    fun stoppingHeartRateRestoresNormalRemoteTakeover() {
        val gate = HeartRateRemoteTakeoverGate()
        gate.onHeartRateRequested()
        assertFalse(gate.onRemoteStreamingStateChanged(true))

        gate.onHeartRateStopped()

        assertTrue(gate.onRemoteStreamingStateChanged(true))
    }
}
