package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class AACPManagerSmartRoutingTest {
    @Test
    fun audioCategoryOneSignalsPlaybackIntentEvenWhileHostStillReportsNo() {
        val state = parseSmartRoutingPlaybackState(
            "playingAppBNA" +
                "RhostStreamingStateBNO" +
                "XotherDeviceAudioCategory1"
        )

        assertEquals(false, state.hostStreaming)
        assertEquals(1, state.otherDeviceAudioCategory)
        assertEquals(false, state.playingAppActive)
        assertEquals(true, state.hasRemotePlaybackIntent)
    }

    @Test
    fun idleMacDoesNotSignalPlaybackIntent() {
        val state = parseSmartRoutingPlaybackState(
            "playingAppBNA" +
                "RhostStreamingStateBNO" +
                "XotherDeviceAudioCategory0"
        )

        assertEquals(false, state.hostStreaming)
        assertEquals(0, state.otherDeviceAudioCategory)
        assertEquals(false, state.hasRemotePlaybackIntent)
    }

    @Test
    fun explicitHostStreamingYesSignalsPlaybackIntent() {
        val state = parseSmartRoutingPlaybackState(
            "RhostStreamingStateCYES" +
                "XotherDeviceAudioCategory0"
        )

        assertEquals(true, state.hostStreaming)
        assertEquals(true, state.hasRemotePlaybackIntent)
    }

    @Test
    fun unrelatedYesDoesNotOverrideEncodedHostStreamingNo() {
        val state = parseSmartRoutingPlaybackState(
            "unrelatedFieldCYES" +
                "RhostStreamingStateBNO"
        )

        assertEquals(false, state.hostStreaming)
        assertEquals(false, state.hasRemotePlaybackIntent)
    }

    @Test
    fun iPadPlayingAppSignalsPlaybackWhenAudioCategoryIsOmitted() {
        val state = parseSmartRoutingPlaybackState(
            "PlayingAppVcom.apple.mobilesafariRHostStreamingStateBNO" +
                "IbtAddressQ11:22:33:44:55:66FbtNameDiPad"
        )

        assertEquals(false, state.hostStreaming)
        assertEquals(null, state.otherDeviceAudioCategory)
        assertEquals(true, state.playingAppActive)
        assertEquals(true, state.hasRemotePlaybackIntent)
    }

    @Test
    fun idleIPhoneWithoutAudioCategoryDoesNotSignalPlayback() {
        val state = parseSmartRoutingPlaybackState(
            "PlayingAppBNARHostStreamingStateBNO" +
                "IbtAddressQ11:22:33:44:55:66FbtNameFiPhone"
        )

        assertEquals(false, state.hostStreaming)
        assertEquals(null, state.otherDeviceAudioCategory)
        assertEquals(false, state.playingAppActive)
        assertEquals(false, state.hasRemotePlaybackIntent)
    }

    @Test
    fun explicitIdleAudioCategoryOverridesForegroundAppleApp() {
        val state = parseSmartRoutingPlaybackState(
            "PlayingAppVcom.apple.mobilesafariRHostStreamingStateBNO" +
                "XotherDeviceAudioCategory0"
        )

        assertEquals(false, state.hostStreaming)
        assertEquals(0, state.otherDeviceAudioCategory)
        assertEquals(true, state.playingAppActive)
        assertEquals(false, state.hasRemotePlaybackIntent)
    }
}
