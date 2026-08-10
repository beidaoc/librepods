/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsHeartRateCapabilityTest {
    @Test
    fun onlyAirPodsPro3AdvertisesHeartRateCapability() {
        val heartRateModels = AirPodsModels.models.filter {
            Capability.HRM in it.capabilities
        }

        assertEquals(1, heartRateModels.size)
        assertEquals(
            setOf("A3063", "A3064", "A3065"),
            heartRateModels.single().modelNumber.toSet()
        )
    }

    @Test
    fun everyKnownNonPro3ModelDoesNotAdvertiseHeartRate() {
        AirPodsModels.models
            .filterNot { it.modelNumber.any(setOf("A3063", "A3064", "A3065")::contains) }
            .forEach { model ->
                assertFalse("${model.name} unexpectedly supports HRM", Capability.HRM in model.capabilities)
            }
    }

    @Test
    fun unknownModelCannotGainHeartRateCapabilityFromFallback() {
        val unknown = AirPodsModels.getModelByModelNumber("UNKNOWN")

        assertNull(unknown)
        assertTrue(unknown?.capabilities?.contains(Capability.HRM) != true)
    }
}
