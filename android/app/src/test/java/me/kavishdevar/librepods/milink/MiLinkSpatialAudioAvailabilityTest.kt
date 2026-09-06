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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiLinkSpatialAudioAvailabilityTest {
    @Test
    fun `shows panel only when every required capability is available`() {
        assertTrue(
            MiLinkSpatialAudioAvailability.isPanelAvailable(
                capabilityChecked = true,
                spatializerAvailable = true,
                helperAvailable = true,
                error = null,
            ),
        )
    }

    @Test
    fun `hides entire panel when any requirement is missing`() {
        assertFalse(MiLinkSpatialAudioAvailability.isPanelAvailable(false, true, true, null))
        assertFalse(MiLinkSpatialAudioAvailability.isPanelAvailable(true, false, true, null))
        assertFalse(MiLinkSpatialAudioAvailability.isPanelAvailable(true, true, false, null))
        assertFalse(MiLinkSpatialAudioAvailability.isPanelAvailable(true, true, true, "failed"))
    }
}
