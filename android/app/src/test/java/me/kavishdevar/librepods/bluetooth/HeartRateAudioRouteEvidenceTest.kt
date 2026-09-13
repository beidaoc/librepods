package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateAudioRouteEvidenceTest {
    private val phone = "88:2F:92:BB:C4:66"
    private val remote = "AA:BB:CC:DD:EE:FF"

    @Test fun localPlaybackConfirmsRouteWithoutOwnershipReply() {
        val evidence = HeartRateAudioRouteEvidence()
        assertFalse(evidence.confirmsLocalRoute(phone))
        evidence.onAudioSource(phone.lowercase(), active = true)
        assertTrue(evidence.confirmsLocalRoute(phone))
    }

    @Test fun pausingMusicKeepsHeartRateEligibleUntilAnotherHostPlays() {
        val evidence = HeartRateAudioRouteEvidence()
        evidence.onAudioSource(phone, active = true)
        evidence.onAudioSource("00:00:00:00:00:00", active = false)
        assertTrue(evidence.confirmsLocalRoute(phone))
        evidence.onAudioSource(remote, active = true)
        assertFalse(evidence.confirmsLocalRoute(phone))
        evidence.onAudioSource("00:00:00:00:00:00", active = false)
        assertFalse(evidence.confirmsLocalRoute(phone))
    }

    @Test fun revocationOrDisconnectRequiresNewLocalEvidence() {
        val evidence = HeartRateAudioRouteEvidence()
        evidence.onAudioSource(phone, active = true)
        evidence.clear()
        evidence.onAudioSource("00:00:00:00:00:00", active = false)
        assertFalse(evidence.confirmsLocalRoute(phone))
        evidence.onAudioSource(phone, active = true)
        assertTrue(evidence.confirmsLocalRoute(phone))
    }

    @Test fun unknownAndPlaceholderAddressesNeverAuthorizeSampling() {
        for (mac in listOf("", "00:00:00:00:00:00", "02:00:00:00:00:00")) {
            val evidence = HeartRateAudioRouteEvidence()
            evidence.onAudioSource(mac, active = true)
            assertFalse(evidence.confirmsLocalRoute(mac))
        }
    }
}
