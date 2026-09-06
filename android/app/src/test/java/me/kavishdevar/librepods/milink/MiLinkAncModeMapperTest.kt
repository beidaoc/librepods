package me.kavishdevar.librepods.milink

import org.junit.Assert.assertEquals
import org.junit.Test

class MiLinkAncModeMapperTest {
    @Test
    fun `maps LibrePods ANC states to MiLink's three controls`() {
        assertEquals(-1, MiLinkAncModeMapper.toMiLink(1))
        assertEquals(0, MiLinkAncModeMapper.toMiLink(2))
        assertEquals(2, MiLinkAncModeMapper.toMiLink(3))
        assertEquals(1, MiLinkAncModeMapper.toMiLink(4))
    }

    @Test
    fun `maps MiLink control clicks to LibrePods AACP modes`() {
        assertEquals(2, MiLinkAncModeMapper.toLibrePods(0))
        assertEquals(4, MiLinkAncModeMapper.toLibrePods(1))
        assertEquals(3, MiLinkAncModeMapper.toLibrePods(2))
    }

    @Test
    fun `unknown MiLink mode fails closed to ANC off`() {
        assertEquals(1, MiLinkAncModeMapper.toLibrePods(-1))
        assertEquals(1, MiLinkAncModeMapper.toLibrePods(99))
    }

    @Test
    fun `only the three visible LibrePods modes can be commanded`() {
        assertEquals(false, MiLinkAncModeMapper.isSelectableLibrePodsMode(1))
        assertEquals(true, MiLinkAncModeMapper.isSelectableLibrePodsMode(2))
        assertEquals(true, MiLinkAncModeMapper.isSelectableLibrePodsMode(3))
        assertEquals(true, MiLinkAncModeMapper.isSelectableLibrePodsMode(4))
    }

    @Test
    fun `maps LibrePods modes to the repurposed detail card positions`() {
        assertEquals(1, MiLinkAncModeMapper.toDetailPresenterMode(3))
        assertEquals(0, MiLinkAncModeMapper.toDetailPresenterMode(4))
        assertEquals(2, MiLinkAncModeMapper.toDetailPresenterMode(2))
        assertEquals(-1, MiLinkAncModeMapper.toDetailPresenterMode(1))
    }

    @Test
    fun `detail mode always follows a selectable LibrePods state`() {
        assertEquals(2, MiLinkAncModeMapper.resolveDisplayMode(-2, 2))
        assertEquals(1, MiLinkAncModeMapper.resolveDisplayMode(0, 3))
        assertEquals(0, MiLinkAncModeMapper.resolveDisplayMode(1, 4))
    }

    @Test
    fun `host mode remains unchanged when LibrePods state is not exposed`() {
        assertEquals(2, MiLinkAncModeMapper.resolveDisplayMode(2, 1))
        assertEquals(-1, MiLinkAncModeMapper.resolveDisplayMode(-1, 1))
        assertEquals(-2, MiLinkAncModeMapper.resolveDisplayMode(-2, 1))
    }
}
