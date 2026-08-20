/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.notifications

object NotificationAnnouncementRoutePolicy {
    fun canAnnounce(
        localOwnsConnection: Boolean,
        remoteDeviceStreaming: Boolean,
        activeAudioSourceIsRemote: Boolean
    ): Boolean = localOwnsConnection && !remoteDeviceStreaming && !activeAudioSourceIsRemote
}
