package me.kavishdevar.librepods.bluetooth

/** Earbud-reported routing evidence, scoped to heart-rate sampling in one AACP session. */
internal class HeartRateAudioRouteEvidence {
    @Volatile private var lastActiveSource: String? = null

    fun onAudioSource(mac: String, active: Boolean) {
        // NONE means playback paused, not that another host acquired the connection.
        if (active) lastActiveSource = mac
    }

    fun confirmsLocalRoute(localMac: String): Boolean =
        localMac.isNotBlank() && localMac != "00:00:00:00:00:00" &&
            localMac != "02:00:00:00:00:00" &&
            lastActiveSource.equals(localMac, ignoreCase = true)

    fun clear() {
        lastActiveSource = null
    }
}
