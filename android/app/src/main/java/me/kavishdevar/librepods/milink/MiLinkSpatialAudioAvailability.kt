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

/** The MiLink row is all-or-nothing because it always exposes head tracking. */
object MiLinkSpatialAudioAvailability {
    fun isPanelAvailable(
        capabilityChecked: Boolean,
        spatializerAvailable: Boolean,
        helperAvailable: Boolean,
        error: String?,
    ): Boolean = capabilityChecked && spatializerAvailable && helperAvailable && error == null
}
