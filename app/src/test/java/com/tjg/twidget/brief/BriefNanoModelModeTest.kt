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
    fun unknownStorageIdFallsBackToStableFull() {
        assertEquals(BriefNanoModelMode.STABLE_FULL, BriefNanoModelMode.fromStorageId(null))
        assertEquals(BriefNanoModelMode.STABLE_FULL, BriefNanoModelMode.fromStorageId("future_mode"))
    }
}
