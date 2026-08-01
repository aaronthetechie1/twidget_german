package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class MilestoneWidgetArtworkRendererTest {
    @Test
    fun `figma 4 by 1 uses wide strip layout`() {
        assertEquals(
            MilestoneWidgetArtworkRenderer.MilestoneWidgetLayout.WIDE_STRIP,
            MilestoneWidgetArtworkRenderer.widgetLayout(352f, 76f),
        )
    }

    @Test
    fun `figma 2 by 2 uses square layout`() {
        assertEquals(
            MilestoneWidgetArtworkRenderer.MilestoneWidgetLayout.SQUARE,
            MilestoneWidgetArtworkRenderer.widgetLayout(162f, 176f),
        )
    }

    @Test
    fun `figma 4 by 2 uses wide tall layout`() {
        assertEquals(
            MilestoneWidgetArtworkRenderer.MilestoneWidgetLayout.WIDE_TALL,
            MilestoneWidgetArtworkRenderer.widgetLayout(352f, 175.33f),
        )
    }

    @Test
    fun `short two column launcher allocation still uses strip layout`() {
        assertEquals(
            MilestoneWidgetArtworkRenderer.MilestoneWidgetLayout.WIDE_STRIP,
            MilestoneWidgetArtworkRenderer.widgetLayout(179f, 72f),
        )
    }
}
