package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefWidgetArtworkRendererTest {
    @Test
    fun mapsEachFigmaWidgetSizeToItsDedicatedComposition() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.COMPACT_STRIP,
            BriefWidgetArtworkRenderer.layout(162f, 76f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_STRIP,
            BriefWidgetArtworkRenderer.layout(352f, 76f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.SQUARE,
            BriefWidgetArtworkRenderer.layout(162f, 176f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(352f, 175f),
        )
    }

    @Test
    fun layoutSelectionToleratesLauncherSizeVariation() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.COMPACT_STRIP,
            BriefWidgetArtworkRenderer.layout(170f, 80f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(340f, 168f),
        )
    }
}
