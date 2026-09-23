package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class AirPods5CapabilityTest {
    @Test
    fun identifiesAllEarbudModelsAndDistinguishesVariants() {
        listOf("A3531", "A3532", "A3533").forEach {
            assertTrue(AirPodsModels.getModelByModelNumber(it) is AirPods5)
        }
        listOf("A3439", "A3440", "A3441").forEach {
            assertTrue(AirPodsModels.getModelByModelNumber(it) is AirPods5Wireless)
        }
        // Charging-case numbers must not identify the connected earbuds.
        assertNull(AirPodsModels.getModelByModelNumber("A3529"))
        assertNull(AirPodsModels.getModelByModelNumber("A3530"))
    }

    @Test
    fun onlyWirelessVariantOffersVolumeSwipe() {
        val standard = AirPods5().capabilities
        val wireless = AirPods5Wireless().capabilities
        assertFalse(Capability.SWIPE_FOR_VOLUME in standard)
        assertEquals(standard + Capability.SWIPE_FOR_VOLUME, wireless)
    }

    @Test
    fun bothVariantsOfferNoiseControlsWithoutProHealthFeatures() {
        for (model in listOf(AirPods5(), AirPods5Wireless())) {
            assertTrue(model.capabilities.containsAll(setOf(
                Capability.LISTENING_MODE, Capability.ADAPTIVE_AUDIO,
                Capability.CONVERSATION_AWARENESS, Capability.STEM_CONFIG
            )))
            for (unsupported in listOf(Capability.HRM, Capability.HEARING_AID,
                Capability.PPE, Capability.LOUD_SOUND_REDUCTION)) {
                assertFalse("${model.name}: $unsupported", unsupported in model.capabilities)
            }
            assertTrue(model.displayName.startsWith("AirPods 5"))
        }
    }

    @Test
    fun everyModelNumberHasExactlyOneMapping() {
        val numbers = AirPodsModels.models.flatMap { it.modelNumber }
        assertEquals(numbers.size, numbers.toSet().size)
    }
}
