package com.tjg.twidget.brief

import com.tjg.twidget.main.MilestoneMetric
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefGoalCopyTest {
    private val strings = TestBriefStrings()

    @Test
    fun followerGoalStatesTheExactRemainingDistance() {
        assertEquals(
            "You’re 264 followers away from your 8,000 follower goal.",
            BriefGoalCopy.remainingBody(MilestoneMetric.FOLLOWERS, 7_736.0, 8_000.0, strings),
        )
    }

    @Test
    fun singularRemainingDistanceUsesSingularNoun() {
        assertEquals(
            "You’re 1 follower away from your 8,000 follower goal.",
            BriefGoalCopy.remainingBody(MilestoneMetric.FOLLOWERS, 7_999.0, 8_000.0, strings),
        )
    }
}
