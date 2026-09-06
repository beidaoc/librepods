/*
 * LibrePods - AirPods liberated from Apple's ecosystem
 * Copyright (C) 2025 LibrePods contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package me.kavishdevar.librepods.milink

/** Converts LibrePods' three spatial-audio modes to HyperOS MiLink values. */
object MiLinkSpatialAudioModeMapper {
    const val LIBREPODS_OFF = 0
    const val LIBREPODS_FIXED = 1
    const val LIBREPODS_HEAD_TRACKED = 2

    const val MI_LINK_OFF = 0
    const val MI_LINK_FIXED = 1
    const val MI_LINK_HEAD_TRACKED_GENERIC = 9
    const val MI_LINK_HEAD_TRACKED_XIAOMI = 11

    const val DEVICE_SPATIAL_TYPE_XIAOMI = 1

    fun toMiLink(
        librePodsMode: Int,
        deviceSpatialType: Int = DEVICE_SPATIAL_TYPE_XIAOMI,
    ): Int = when (librePodsMode) {
        LIBREPODS_FIXED -> MI_LINK_FIXED
        LIBREPODS_HEAD_TRACKED -> if (deviceSpatialType == DEVICE_SPATIAL_TYPE_XIAOMI) {
            MI_LINK_HEAD_TRACKED_XIAOMI
        } else {
            MI_LINK_HEAD_TRACKED_GENERIC
        }
        else -> MI_LINK_OFF
    }

    fun toLibrePods(miLinkMode: Int): Int = when (miLinkMode) {
        MI_LINK_FIXED -> LIBREPODS_FIXED
        MI_LINK_HEAD_TRACKED_GENERIC, MI_LINK_HEAD_TRACKED_XIAOMI ->
            LIBREPODS_HEAD_TRACKED
        else -> LIBREPODS_OFF
    }

    fun isSelectableLibrePodsMode(mode: Int): Boolean =
        mode in LIBREPODS_OFF..LIBREPODS_HEAD_TRACKED
}
