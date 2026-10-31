package com.jun9100.opensave.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutInfoTest {
    @Test
    fun odin2LandscapeIsHandheldLandscape() {
        // Measured window, and the edge-to-edge window that includes both bars.
        listOf(AdaptiveLayoutInfo(833f, 444f), AdaptiveLayoutInfo(833f, 468f)).forEach { info ->
            assertTrue(info.isLandscape)
            assertEquals(AdaptiveMode.HANDHELD_LANDSCAPE, info.mode)
        }
    }

    @Test
    fun odin2PortraitIsCompactAlthoughItIsNotNarrow() {
        val info = AdaptiveLayoutInfo(468f, 809f)
        assertFalse(info.isLandscape)
        assertEquals(AdaptiveMode.COMPACT, info.mode)
    }

    @Test
    fun narrowLandscapeAndSquareWindowsStayCompact() {
        assertEquals(AdaptiveMode.COMPACT, AdaptiveLayoutInfo(560f, 360f).mode)
        assertEquals(AdaptiveMode.COMPACT, AdaptiveLayoutInfo(700f, 700f).mode)
    }

    @Test
    fun tabletLandscapeIsExpandedWithAWiderList() {
        val info = AdaptiveLayoutInfo(1280f, 800f)
        assertEquals(AdaptiveMode.EXPANDED, info.mode)
        assertEquals(400f, info.listPaneWidthDp)
        assertEquals(360f, AdaptiveLayoutInfo(833f, 444f).listPaneWidthDp)
    }
}
