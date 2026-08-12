package me.kavishdevar.librepods.keepbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepHeartRateStateTest {
    @Test
    fun validStreamingSampleIsActiveUntilTimeout() {
        val state = KeepHeartRateState()
        state.update(true, true, 76, 1_000L)

        assertTrue(state.isActive(1_000L))
        assertEquals(76, state.getBpm(1_000L))
        assertTrue(state.isActive(1_000L + KeepHeartRateState.STALE_AFTER_MILLIS))
        assertFalse(state.isActive(1_001L + KeepHeartRateState.STALE_AFTER_MILLIS))
        assertEquals(-1, state.getBpm(1_001L + KeepHeartRateState.STALE_AFTER_MILLIS))
    }

    @Test
    fun disabledStoppedAndInvalidSamplesAreRejected() {
        val state = KeepHeartRateState()

        state.update(false, true, 76, 1_000L)
        assertFalse(state.isActive(1_000L))

        state.update(true, false, 76, 1_000L)
        assertFalse(state.isActive(1_000L))

        state.update(true, true, 29, 1_000L)
        assertFalse(state.isActive(1_000L))

        state.update(true, true, 221, 1_000L)
        assertFalse(state.isActive(1_000L))
    }
}
