package com.tjg.twidget.analytics

data class PostLink(
    val display: String,
    val url: String,
)

data class PostMedia(
    val type: String,
    val url: String,
    val alt: String,
    val width: Long,
    val height: Long,
)

/** One post in the best/worst pair. */
data class PostSummary(
    val url: String,
    val text: String,
    val views: Long,
    val likes: Long,
    val replies: Long,
    val reposts: Long,
    val quotes: Long,
    val engagements: Long,
    val timestamp: Long,
    val createdAt: String,
    val authorName: String,
    val authorUserName: String,
    val authorAvatar: String,
    val links: List<PostLink> = emptyList(),
    val media: List<PostMedia> = emptyList(),
)

/**
 * Reach and engagement over the recent timeline, plus the 7-day best/worst
 * posts. Sourced directly from FxTwitter or TwitterAPIs, or from the selected
 * bridge, and cached per provider and account.
 */
data class PostAnalytics(
    val userName: String,
    val followers: Long,
    val postsAnalyzed: Int,
    /** Timeline rows inspected before replies/reposts/old posts were removed. */
    val statusesInspected: Int = postsAnalyzed,
    /** True when the bounded FxTwitter walk stopped before the timeline ended. */
    val isSampled: Boolean = false,
    val windowDays: Int,
    val totalViews: Long,
    val avgViews: Double,
    val medianViews: Double,
    val avgViewsPerFollower: Double,
    val totalEngagements: Long,
    val avgEngagements: Double,
    val medianEngagements: Double,
    val avgEngagementsPerFollower: Double,
    val engagementRate: Double,
    val best: PostSummary?,
    val worst: PostSummary?,
    val banger: PostSummary? = null,
    val bangerComplete: Boolean = false,
    val bangerPostsScanned: Int = 0,
    val cachedAt: Long,
    val medianLikes: Double = 0.0,
    val medianReplies: Double = 0.0,
    val medianShares: Double = 0.0,
    /** Recent original posts retained locally for evidence-based Brief guidance. */
    val recentPosts: List<PostSummary> = emptyList(),
)

enum class TweetPerformanceDirection { STRONG, QUIET }

data class TweetPerformanceExplanation(
    val title: String,
    val body: String,
    val confidence: Int,
)

/** Evidence-only comparisons against this account's own weekly baseline. */
object TweetPerformanceExplainer {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun quietTweetEligible(post: PostSummary?, analytics: PostAnalytics, now: Long = System.currentTimeMillis()): Boolean =
        post != null && analytics.postsAnalyzed >= 2 && post.timestamp > 0L && now - post.timestamp >= DAY_MS

    fun explain(
        post: PostSummary,
        analytics: PostAnalytics,
        direction: TweetPerformanceDirection,
    ): TweetPerformanceExplanation {
        val viewRatio = ratio(post.views.toDouble(), analytics.medianViews)
        val postRate = if (post.views > 0L) post.engagements.toDouble() / post.views else 0.0
        val rateRatio = ratio(postRate, analytics.engagementRate)
        val shareRatio = ratio((post.reposts + post.quotes).toDouble(), analytics.medianShares)
        val replyRatio = ratio(post.replies.toDouble(), analytics.medianReplies)
        val likeRatio = ratio(post.likes.toDouble(), analytics.medianLikes)

        val observations = when (direction) {
            TweetPerformanceDirection.STRONG -> buildList {
                if (viewRatio >= 1.5) add("It reached substantially more people than your typical tweet this week.")
                if (rateRatio >= 1.25) add("Viewers interacted at a higher rate than usual.")
                if (shareRatio >= 1.5 && post.reposts + post.quotes >= 2) add("Sharing contributed more strongly than on your other tweets.")
                if (replyRatio >= 1.5 && post.replies >= 2) add("It generated more conversation than your weekly baseline.")
                if (likeRatio >= 1.5 && post.likes >= 2) add("It drew more likes than your typical tweet this week.")
            }
            TweetPerformanceDirection.QUIET -> buildList {
                if (viewRatio in 0.0..0.65) add("It reached fewer people than your typical tweet this week.")
                if (rateRatio in 0.0..0.75 && post.views > 0L) add("Reach converted into fewer interactions than usual.")
                if (shareRatio in 0.0..0.65) add("It generated fewer shares than your weekly baseline.")
                if (replyRatio in 0.0..0.65) add("It generated less conversation than your typical tweet.")
                if (likeRatio in 0.0..0.65) add("It drew fewer likes than your weekly norm.")
            }
        }
        val body = observations.distinct().take(2).joinToString(" ").ifBlank {
            if (analytics.postsAnalyzed < 3 || analytics.isSampled) {
                "There isn’t enough complete data yet to identify a clear pattern."
            } else if (direction == TweetPerformanceDirection.STRONG) {
                "It performed well overall, without one metric clearly explaining the result."
            } else {
                "It was this week’s quietest tweet, but no single metric clearly explains the result."
            }
        }
        val confidence = when {
            analytics.isSampled || analytics.postsAnalyzed < 3 -> 1
            observations.size >= 2 && analytics.postsAnalyzed >= 5 -> 3
            else -> 2
        }
        return TweetPerformanceExplanation(
            title = if (direction == TweetPerformanceDirection.STRONG) "Why this tweet worked" else "What may have limited it",
            body = body,
            confidence = confidence,
        )
    }

    private fun ratio(value: Double, baseline: Double): Double =
        if (baseline > 0.0 && baseline.isFinite()) value / baseline else 1.0
}
