package me.kavishdevar.librepods.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAnnouncementRoutePolicyTest {
    @Test
    fun `allows announcement only when phone owns the AirPods audio route`() {
        assertTrue(
            NotificationAnnouncementRoutePolicy.canAnnounce(
                localOwnsConnection = true,
                remoteDeviceStreaming = false,
                activeAudioSourceIsLocal = false,
                activeAudioSourceIsRemote = false
            )
        )
    }

    @Test
    fun `allows announcement when AirPods confirm this phone is the active audio source`() {
        assertTrue(
            NotificationAnnouncementRoutePolicy.canAnnounce(
                localOwnsConnection = false,
                remoteDeviceStreaming = false,
                activeAudioSourceIsLocal = true,
                activeAudioSourceIsRemote = false
            )
        )
    }

    @Test
    fun `blocks announcement while Mac or another remote device is streaming`() {
        assertFalse(
            NotificationAnnouncementRoutePolicy.canAnnounce(
                localOwnsConnection = true,
                remoteDeviceStreaming = true,
                activeAudioSourceIsLocal = true,
                activeAudioSourceIsRemote = false
            )
        )
        assertFalse(
            NotificationAnnouncementRoutePolicy.canAnnounce(
                localOwnsConnection = true,
                remoteDeviceStreaming = false,
                activeAudioSourceIsLocal = false,
                activeAudioSourceIsRemote = true
            )
        )
    }

    @Test
    fun `fails closed while local ownership is unknown or lost`() {
        assertFalse(
            NotificationAnnouncementRoutePolicy.canAnnounce(
                localOwnsConnection = false,
                remoteDeviceStreaming = false,
                activeAudioSourceIsLocal = false,
                activeAudioSourceIsRemote = false
            )
        )
    }
}
