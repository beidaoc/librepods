/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.notifications

import android.os.SystemClock

/**
 * Distinguishes LibrePods' notification speech from user-initiated media playback.
 *
 * Audio focus and playback callbacks can arrive synchronously, so this is set before
 * requesting focus. The short tail also covers callbacks caused by focus restoration.
 */
object NotificationAnnouncementPlayback {
    @Volatile
    private var activeUntilElapsedRealtime = 0L

    fun begin() {
        activeUntilElapsedRealtime = Long.MAX_VALUE
    }

    fun end() {
        activeUntilElapsedRealtime = SystemClock.elapsedRealtime() + RESTORE_GRACE_MS
    }

    fun isActive(): Boolean = SystemClock.elapsedRealtime() < activeUntilElapsedRealtime

    private const val RESTORE_GRACE_MS = 1_500L
}
