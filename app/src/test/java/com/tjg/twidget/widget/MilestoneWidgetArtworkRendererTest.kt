package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class MilestoneWidgetArtworkRendererTest {
    @Test
    fun `figma 4 by 1 uses wide strip layout`() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_STRIP,
            BriefWidgetArtworkRenderer.layout(352f, 76f),
        )
    }

    @Test
    fun `figma 2 by 2 uses square layout`() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.SQUARE,
            BriefWidgetArtworkRenderer.layout(162f, 176f),
        )
    }

    @Test
    fun `figma 4 by 2 uses wide tall layout`() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(352f, 175.33f),
        )
    }

    @Test
    fun `short two column launcher allocation still uses strip layout`() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.COMPACT_STRIP,
            BriefWidgetArtworkRenderer.layout(179f, 72f),
        )
    }
}
