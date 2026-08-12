package me.kavishdevar.librepods.services

/**
 * Distinguishes the remote playback state that was already active when heart-rate ownership was
 * requested from a new playback attempt made after the remote host has paused.
 */
internal class HeartRateRemoteTakeoverGate {
    private var heartRateRequested = false
    private var remoteStreaming = false
    private var armedForNewRemotePlayback = true

    @Synchronized
    fun onHeartRateRequested() {
        heartRateRequested = true
        remoteStreaming = false
        armedForNewRemotePlayback = false
    }

    @Synchronized
    fun onHeartRateStopped() {
        heartRateRequested = false
        remoteStreaming = false
        armedForNewRemotePlayback = true
    }

    @Synchronized
    fun onHeartRateStreamingStarted() {
        if (!heartRateRequested) return
        // Reaching a validated BPM proves that the initial ownership transfer completed. Any
        // subsequent remote playback report is therefore a new takeover attempt, even when the
        // remote host never emitted an explicit idle report while it was paused.
        remoteStreaming = false
        armedForNewRemotePlayback = true
    }

    /** Returns true only when this state change should release ownership to the remote host. */
    @Synchronized
    fun onRemoteStreamingStateChanged(isStreaming: Boolean): Boolean {
        if (!heartRateRequested) {
            remoteStreaming = isStreaming
            return isStreaming
        }

        if (!isStreaming) {
            remoteStreaming = false
            armedForNewRemotePlayback = true
            return false
        }

        val isNewPlaybackEdge = !remoteStreaming
        remoteStreaming = true
        return armedForNewRemotePlayback && isNewPlaybackEdge
    }
}
