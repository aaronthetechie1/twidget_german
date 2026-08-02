package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefLaunchGenerationTest {
    @Test
    fun continueUsesSpinnerWhileGenerationIsRunning() {
        assertEquals(
            BriefContinueDestination.SPINNER,
            briefContinueDestination(generationComplete = false),
        )
    }

    @Test
    fun continueOpensBriefWhenGenerationIsComplete() {
        assertEquals(
            BriefContinueDestination.BRIEF,
            briefContinueDestination(generationComplete = true),
        )
    }
}
