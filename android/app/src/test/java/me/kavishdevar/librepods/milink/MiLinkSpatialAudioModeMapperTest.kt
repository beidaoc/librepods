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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiLinkSpatialAudioModeMapperTest {
    @Test
    fun `maps all LibrePods modes to the Xiaomi MiLink card`() {
        assertEquals(0, MiLinkSpatialAudioModeMapper.toMiLink(0))
        assertEquals(1, MiLinkSpatialAudioModeMapper.toMiLink(1))
        assertEquals(11, MiLinkSpatialAudioModeMapper.toMiLink(2))
    }

    @Test
    fun `accepts both HyperOS head tracking values`() {
        assertEquals(0, MiLinkSpatialAudioModeMapper.toLibrePods(0))
        assertEquals(1, MiLinkSpatialAudioModeMapper.toLibrePods(1))
        assertEquals(2, MiLinkSpatialAudioModeMapper.toLibrePods(9))
        assertEquals(2, MiLinkSpatialAudioModeMapper.toLibrePods(11))
    }

    @Test
    fun `rejects out of range LibrePods modes`() {
        assertTrue(MiLinkSpatialAudioModeMapper.isSelectableLibrePodsMode(0))
        assertTrue(MiLinkSpatialAudioModeMapper.isSelectableLibrePodsMode(2))
        assertFalse(MiLinkSpatialAudioModeMapper.isSelectableLibrePodsMode(-1))
        assertFalse(MiLinkSpatialAudioModeMapper.isSelectableLibrePodsMode(3))
    }
}
