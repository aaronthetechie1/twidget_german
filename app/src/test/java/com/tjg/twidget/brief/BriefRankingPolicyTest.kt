package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.main.MilestonePerformanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefRankingPolicyTest {
    @Test
    fun ordersCardsByEngineScoreRatherThanMockupOrTypeOrder() {
        val cards = listOf(
            BriefCard("post", BriefCardType.POST, "Post", "", 72),
            BriefCard("milestone", BriefCardType.MILESTONE, "Goal", "", 88),
            BriefCard("slowdown", BriefCardType.SLOWDOWN, "Slow", "", 96),
        )

        assertEquals(
            listOf("slowdown", "milestone", "post"),
            BriefRankingPolicy.order(cards).map(BriefCard::id),
        )
    }

    @Test
    fun accountActivityChangesGrowthPriority() {
        val quiet = BriefRankingPolicy.growth(today = 5, week = 15, weeklyPercent = 2.0)
        val surging = BriefRankingPolicy.growth(today = 25, week = 80, weeklyPercent = 8.0)

        assertTrue(surging > quiet)
    }

    @Test
    fun standoutPostIsRelativeToAccountSize() {
        val post = PostSummary(
            url = "",
            text = "",
            views = 20_000,
            likes = 150,
            replies = 0,
            reposts = 0,
            quotes = 0,
            engagements = 150,
            timestamp = 0,
            createdAt = "",
            authorName = "",
            authorUserName = "",
            authorAvatar = "",
        )

        assertTrue(
            BriefRankingPolicy.post(post, followers = 1_000) >
                BriefRankingPolicy.post(post, followers = 100_000),
        )
    }

    @Test
    fun urgentStreakOutranksAnAlreadySafeStreak() {
        assertTrue(
            BriefRankingPolicy.streak(days = 10, activeToday = false) >
                BriefRankingPolicy.streak(days = 10, activeToday = true),
        )
    }

    @Test
    fun nearbyOrSlippingGoalGetsMorePriority() {
        val early = BriefRankingPolicy.milestone(25, MilestonePerformanceState.NEUTRAL)
        val close = BriefRankingPolicy.milestone(90, MilestonePerformanceState.NEUTRAL)
        val slipping = BriefRankingPolicy.milestone(90, MilestonePerformanceState.DECELERATING)

        assertTrue(close > early)
        assertTrue(slipping > close)
    }
}
