package com.tjg.twidget.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweetPerformanceExplainerTest {
    @Test
    fun explainsStrongTweetFromOwnWeeklyBaseline() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 2_000, likes = 40, replies = 8, reposts = 10, quotes = 2),
            analytics(),
            TweetPerformanceDirection.STRONG,
        )
        assertTrue(explanation.body.contains("more people"))
        assertTrue(explanation.body.contains("higher rate"))
    }

    @Test
    fun explainsQuietTweetWithoutInventingACause() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 300, likes = 2, replies = 0, reposts = 0, quotes = 0),
            analytics(),
            TweetPerformanceDirection.QUIET,
        )
        assertTrue(explanation.body.contains("fewer people"))
        assertFalse(explanation.body.contains("because", ignoreCase = true))
    }

    @Test
    fun freshTweetIsNotEligibleAsQuietest() {
        val now = 1_800_000_000_000L
        assertFalse(
            TweetPerformanceExplainer.quietTweetEligible(
                post(timestamp = now - 60 * 60 * 1000L),
                analytics(),
                now,
            ),
        )
        assertTrue(
            TweetPerformanceExplainer.quietTweetEligible(
                post(timestamp = now - 25 * 60 * 60 * 1000L),
                analytics(),
                now,
            ),
        )
    }

    private fun analytics() = PostAnalytics(
        userName = "person",
        followers = 1_000,
        postsAnalyzed = 6,
        windowDays = 7,
        totalViews = 6_000,
        avgViews = 1_000.0,
        medianViews = 1_000.0,
        avgViewsPerFollower = 1.0,
        totalEngagements = 120,
        avgEngagements = 20.0,
        medianEngagements = 20.0,
        avgEngagementsPerFollower = 0.02,
        engagementRate = 0.02,
        best = null,
        worst = null,
        cachedAt = 1L,
        medianLikes = 10.0,
        medianReplies = 2.0,
        medianShares = 2.0,
    )

    private fun post(
        views: Long = 100,
        likes: Long = 1,
        replies: Long = 1,
        reposts: Long = 1,
        quotes: Long = 0,
        timestamp: Long = 1L,
    ) = PostSummary(
        url = "https://x.com/person/status/1",
        text = "Tweet",
        views = views,
        likes = likes,
        replies = replies,
        reposts = reposts,
        quotes = quotes,
        engagements = likes + replies + reposts + quotes,
        timestamp = timestamp,
        createdAt = "",
        authorName = "Person",
        authorUserName = "person",
        authorAvatar = "",
    )
}
