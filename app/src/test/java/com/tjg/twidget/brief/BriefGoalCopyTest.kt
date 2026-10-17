package com.tjg.twidget.brief

import com.tjg.twidget.main.MilestoneMetric
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefGoalCopyTest {
    private val strings = TestBriefStrings()

    @Test fun cardDetailShowsMeasuredCountTargetAndProgress() {
        assertEquals("160 / 200 followers · 80%", BriefGoalCopy.cardDetail(MilestoneMetric.FOLLOWERS, 160.0, 200.0, strings))
        assertEquals("1 / 1 follower · 100%", BriefGoalCopy.cardDetail(MilestoneMetric.FOLLOWERS, 1.0, 1.0, strings))
        assertEquals("2.5% / 5% engagement rate · 50%", BriefGoalCopy.cardDetail(MilestoneMetric.ENGAGEMENT_RATE, 0.025, 0.05, strings))
    }

    @Test fun unavailableMeasurementKeepsTheTargetWithoutInventingZeroProgress() {
        assertEquals("Your goal: 200 followers · current value unavailable", BriefGoalCopy.cardDetail(MilestoneMetric.FOLLOWERS, null, 200.0, strings))
    }

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
