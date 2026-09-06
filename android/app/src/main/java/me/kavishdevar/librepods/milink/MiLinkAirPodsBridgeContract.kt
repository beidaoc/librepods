/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package me.kavishdevar.librepods.milink

object MiLinkAirPodsBridgeContract {
    const val MI_LINK_PACKAGE = "com.milink.service"
    const val LIBREPODS_PACKAGE = "me.kavishdevar.librepods"

    const val ACTION_REQUEST_STATE = "$LIBREPODS_PACKAGE.milink.REQUEST_STATE"
    const val ACTION_STATE_CHANGED = "$LIBREPODS_PACKAGE.milink.STATE_CHANGED"
    const val ACTION_SET_ANC = "$LIBREPODS_PACKAGE.milink.SET_ANC"
    const val ACTION_SET_SPATIAL_AUDIO = "$LIBREPODS_PACKAGE.milink.SET_SPATIAL_AUDIO"

    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_NAME = "name"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_ANC_MODE = "anc_mode"
    const val EXTRA_SPATIAL_AUDIO_MODE = "spatial_audio_mode"
    const val EXTRA_SPATIAL_AUDIO_AVAILABLE = "spatial_audio_available"
    const val EXTRA_LEFT_BATTERY = "left_battery"
    const val EXTRA_RIGHT_BATTERY = "right_battery"
    const val EXTRA_CASE_BATTERY = "case_battery"
    const val EXTRA_LEFT_CHARGING = "left_charging"
    const val EXTRA_RIGHT_CHARGING = "right_charging"
    const val EXTRA_CASE_CHARGING = "case_charging"

    const val PROTOCOL_VERSION = 1
}
