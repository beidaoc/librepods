package me.kavishdevar.librepods.keepbridge

import android.content.Context
import android.content.Intent
import me.kavishdevar.librepods.bluetooth.AirPodsHeartRateSample

/**
 * Publishes validated AirPods heart-rate state only to Keep. The receiving dynamic receiver
 * requires [PERMISSION], so another application cannot spoof samples into the injected bridge.
 */
object KeepHeartRateBridge {
    const val KEEP_PACKAGE = "com.gotokeep.keep"
    const val ACTION_STATE =
        "me.kavishdevar.librepods.action.KEEP_HEART_RATE_STATE"
    const val PERMISSION =
        "me.kavishdevar.librepods.permission.HEART_RATE_BRIDGE"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_STREAMING = "streaming"
    const val EXTRA_BPM = "bpm"
    const val EXTRA_SEQUENCE = "sequence"
    const val EXTRA_RECEIVED_AT_MILLIS = "received_at_millis"

    fun publish(
        context: Context,
        enabled: Boolean,
        streaming: Boolean,
        sample: AirPodsHeartRateSample?
    ) {
        val intent = Intent(ACTION_STATE)
            .setPackage(KEEP_PACKAGE)
            .putExtra(EXTRA_ENABLED, enabled)
            .putExtra(EXTRA_STREAMING, streaming)
            .putExtra(EXTRA_BPM, sample?.bpm ?: -1)
            .putExtra(EXTRA_SEQUENCE, sample?.sequence ?: -1L)
            .putExtra(EXTRA_RECEIVED_AT_MILLIS, sample?.receivedAtMillis ?: -1L)
        context.sendBroadcast(intent)
    }
}
