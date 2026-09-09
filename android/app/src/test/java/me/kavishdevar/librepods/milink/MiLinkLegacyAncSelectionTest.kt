package me.kavishdevar.librepods.milink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiLinkLegacyAncSelectionTest {
    @Test
    fun `legacy click round trips to the same visible button`() {
        val expectedCommands = mapOf(1 to 3, 0 to 4, 2 to 2)
        expectedCommands.forEach { (presenter, command) ->
            assertEquals(command, MiLinkLegacyAncSelection.commandMode(presenter))
            assertEquals(presenter, MiLinkLegacyAncSelection.displayMode(command, null, 0))
        }
    }

    @Test
    fun `unsupported host values cannot send an ANC command`() {
        listOf(-2, -1, 3, 99).forEach {
            assertNull(MiLinkLegacyAncSelection.commandMode(it))
        }
    }

    @Test
    fun `stale state cannot undo a click while confirmation is pending`() {
        val pending = MiLinkLegacyAncSelection.Pending(4, 2500)
        val retained = MiLinkLegacyAncSelection.pendingAfterReport(pending, 3, 1000)
        assertEquals(pending, retained)
        assertEquals(0, MiLinkLegacyAncSelection.displayMode(3, retained, 1000))
    }

    @Test
    fun `matching earbud state confirms selection and allows later physical changes`() {
        val pending = MiLinkLegacyAncSelection.Pending(4, 2500)
        val confirmed = MiLinkLegacyAncSelection.pendingAfterReport(pending, 4, 1000)
        assertNull(confirmed)
        assertEquals(2, MiLinkLegacyAncSelection.displayMode(2, confirmed, 1100))
    }

    @Test
    fun `timeout restores latest reported mode at the deadline`() {
        val pending = MiLinkLegacyAncSelection.Pending(4, 2500)
        assertEquals(0, MiLinkLegacyAncSelection.displayMode(3, pending, 2499))
        assertEquals(1, MiLinkLegacyAncSelection.displayMode(3, pending, 2500))
        assertNull(MiLinkLegacyAncSelection.pendingAfterReport(pending, 3, 2500))
    }

    @Test
    fun `a second click is not confirmed by the first click's delayed report`() {
        val latest = MiLinkLegacyAncSelection.Pending(2, 3500)
        assertEquals(latest, MiLinkLegacyAncSelection.pendingAfterReport(latest, 4, 2000))
        assertEquals(2, MiLinkLegacyAncSelection.displayMode(4, latest, 2000))
    }

    @Test
    fun `battery or initial state broadcasts cannot falsely confirm a pending command`() {
        val pending = MiLinkLegacyAncSelection.Pending(3, 2500)
        assertEquals(
            pending,
            MiLinkLegacyAncSelection.pendingAfterReport(pending, 3, 1000, isAncReport = false),
        )
        assertNull(MiLinkLegacyAncSelection.pendingAfterReport(pending, 3, 1100, isAncReport = true))
    }

    @Test
    fun `off and unknown earbud states have no selected control`() {
        assertEquals(-1, MiLinkLegacyAncSelection.displayMode(1, null, 0))
        assertEquals(-1, MiLinkLegacyAncSelection.displayMode(99, null, 0))
    }
}
