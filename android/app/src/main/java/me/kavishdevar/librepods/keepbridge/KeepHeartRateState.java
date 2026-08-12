package me.kavishdevar.librepods.keepbridge;

/** Thread-safe state shared by Keep hooks and the LibrePods broadcast receiver. */
public final class KeepHeartRateState {
    public static final long STALE_AFTER_MILLIS = 6_500L;
    public static final int MIN_BPM = 30;
    public static final int MAX_BPM = 220;

    private boolean enabled;
    private boolean streaming;
    private int bpm = -1;
    private long receivedAtElapsedRealtime;

    public synchronized void update(
            boolean enabled,
            boolean streaming,
            int bpm,
            long receivedAtElapsedRealtime
    ) {
        this.enabled = enabled;
        this.streaming = streaming;
        this.bpm = bpm;
        this.receivedAtElapsedRealtime = receivedAtElapsedRealtime;
    }

    public synchronized boolean isActive(long nowElapsedRealtime) {
        return enabled
                && streaming
                && bpm >= MIN_BPM
                && bpm <= MAX_BPM
                && receivedAtElapsedRealtime > 0L
                && nowElapsedRealtime - receivedAtElapsedRealtime <= STALE_AFTER_MILLIS;
    }

    public synchronized int getBpm(long nowElapsedRealtime) {
        return isActive(nowElapsedRealtime) ? bpm : -1;
    }
}
