package com.tjg.twidget.widget

import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCardType
import dev.oneuiproject.oneui.R as OneUiIconR
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
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.MEDIUM_TALL,
            BriefWidgetArtworkRenderer.layout(270f, 176f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(300f, 149f),
        )
    }

    @Test
    fun supportingArtworkFollowsTheDynamicCardType() {
        assertEquals(
            R.drawable.ic_import_analytics,
            BriefWidgetArtworkRenderer.supportingIcon(BriefCardType.GROWTH),
        )
        assertEquals(
            R.drawable.ic_milestone_goals,
            BriefWidgetArtworkRenderer.supportingIcon(BriefCardType.MILESTONE),
        )
        assertEquals(
            OneUiIconR.drawable.ic_oui_community,
            BriefWidgetArtworkRenderer.supportingIcon(BriefCardType.TOP_FOLLOWER),
        )
    }
}
