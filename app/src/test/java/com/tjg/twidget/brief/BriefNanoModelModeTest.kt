package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefNanoModelModeTest {
    @Test
    fun storageIdsRoundTrip() {
        BriefNanoModelMode.entries.forEach { mode ->
            assertEquals(mode, BriefNanoModelMode.fromStorageId(mode.storageId))
        }
    }

    @Test
    fun unknownStorageIdFallsBackToStableFast() {
        assertEquals(BriefNanoModelMode.STABLE_FAST, BriefNanoModelMode.fromStorageId(null))
        assertEquals(BriefNanoModelMode.STABLE_FAST, BriefNanoModelMode.fromStorageId("future_mode"))
    }
}
