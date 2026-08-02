package com.tjg.twidget.brief

import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus

internal const val BRIEF_MILESTONE_SETUP_ACTION = "setup_account_goals"

enum class BriefProviderMode(val storageId: String) {
    AUTO("auto"),
    LOCAL("local"),
    CLOUD("cloud");

    companion object {
        fun fromStorageId(value: String?): BriefProviderMode =
            entries.firstOrNull { it.storageId == value } ?: AUTO
    }
}

enum class BriefProviderUsed { TEMPLATE, LOCAL, CLOUD }

enum class BriefCardType {
    SUMMARY,
    GROWTH,
    SLOWDOWN,
    INACTIVITY,
    MILESTONE,
    POST,
    WORST_POST,
    TOP_FOLLOWER,
    STREAK,
    SCHEDULE_GUIDE,
    POST_FOLLOW_THROUGH,
    POSTING_GUIDE,
}

enum class BriefCardAction {
    NONE,
    OPEN_SCHEDULER,
    COMPOSE_TWEET,
    OPEN_POST,
}

/**
 * Signals used to decide when a card is useful, independently of its base importance.
 * All values are deliberately data driven so the language model never has to invent rank.
 */
data class BriefRankSignals(
    val contextRelevance: Double = 0.5,
    val timeRelevance: Double = 0.5,
    val occurredAt: Long = 0L,
    val freshForMillis: Long = 0L,
    val validUntil: Long = 0L,
    val maintainUntil: Long = 0L,
)

enum class BriefContentCategory(val storageId: String) {
    TOP_TWEET("top_tweet"),
    WORST_TWEET("worst_tweet"),
    FOLLOWERS("followers"),
    TOP_FOLLOWERS("top_followers"),
    TWEET_ACTIVITY("tweet_activity"),
    SCHEDULED_TWEETS("scheduled_tweets"),
    SCHEDULE_HEALTH("schedule_health"),
    POST_FOLLOW_THROUGH("post_follow_through"),
    POSTING_GUIDANCE("posting_guidance"),
    ACCOUNT_GOALS("account_goals");

    fun includes(type: BriefCardType): Boolean = when (this) {
        TOP_TWEET -> type == BriefCardType.POST
        WORST_TWEET -> type == BriefCardType.WORST_POST
        FOLLOWERS -> type in setOf(
            BriefCardType.SUMMARY,
            BriefCardType.GROWTH,
            BriefCardType.SLOWDOWN,
        )
        TOP_FOLLOWERS -> type == BriefCardType.TOP_FOLLOWER
        TWEET_ACTIVITY -> type in setOf(BriefCardType.INACTIVITY, BriefCardType.STREAK)
        SCHEDULED_TWEETS -> false
        SCHEDULE_HEALTH -> type == BriefCardType.SCHEDULE_GUIDE
        POST_FOLLOW_THROUGH -> type == BriefCardType.POST_FOLLOW_THROUGH
        POSTING_GUIDANCE -> type == BriefCardType.POSTING_GUIDE
        ACCOUNT_GOALS -> type == BriefCardType.MILESTONE
    }

    companion object {
        fun forCard(type: BriefCardType): BriefContentCategory? =
            entries.firstOrNull { it.includes(type) }
    }
}

data class BriefCard(
    val id: String,
    val type: BriefCardType,
    val title: String,
    val body: String,
    val score: Int,
    val action: BriefCardAction = BriefCardAction.NONE,
    val actionData: String = "",
    val sourceAttribution: String = "",
    val rankSignals: BriefRankSignals = BriefRankSignals(),
    val rankingScore: Int = -1,
)

data class BriefUpcomingTweet(
    val id: String,
    val provider: ScheduleProvider,
    val status: ScheduleStatus,
    val scheduledAt: Long,
    val preview: String,
    val threadCount: Int,
    val mediaCount: Int,
    val errorMessage: String = "",
)

data class BriefEditorialSummary(
    val title: String,
    val body: String,
) {
    companion object {
        fun from(snapshot: BriefSnapshot): BriefEditorialSummary = from(
            cards = snapshot.cards,
            followersToday = snapshot.followersToday,
            followersWeek = snapshot.followersWeek,
            upcomingTweets = snapshot.upcomingTweets.size,
        )

        internal fun from(
            cards: List<BriefCard>,
            followersToday: Long = 0L,
            followersWeek: Long = 0L,
            upcomingTweets: Int = 0,
        ): BriefEditorialSummary {
            val types = cards.mapTo(linkedSetOf(), BriefCard::type)
            val hasGoal = cards.any {
                it.type == BriefCardType.MILESTONE &&
                    it.actionData != BRIEF_MILESTONE_SETUP_ACTION
            }
            val title = when {
                hasGoal && (followersToday > 0L || followersWeek > 0L) -> "Moving closer"
                followersToday > 0L || followersWeek > 0L -> "Momentum is building"
                followersToday < 0L || followersWeek < 0L -> "A moment to reset"
                BriefCardType.POSTING_GUIDE in types || BriefCardType.SCHEDULE_GUIDE in types -> "Your next move"
                cards.isNotEmpty() -> "Your week at a glance"
                else -> "Your Twidget Brief"
            }
            val facts = buildList {
                followerOverview(followersToday, followersWeek)?.let(::add)
                if (hasGoal) {
                    add(
                        if (followersToday > 0L || followersWeek > 0L) {
                            "That progress brings your goal closer."
                        } else {
                            "Your goal is still in view."
                        },
                    )
                }
                when {
                    BriefCardType.POST in types && BriefCardType.WORST_POST in types ->
                        add("One recent tweet stood out, while another gives you something to learn from.")
                    BriefCardType.POST in types -> add("One recent tweet stood out from your usual performance.")
                    BriefCardType.WORST_POST in types -> add("One recent tweet gives you something to learn from.")
                }
                if (upcomingTweets > 0) {
                    add(
                        if (upcomingTweets == 1) "One scheduled tweet is ready ahead."
                        else "$upcomingTweets scheduled tweets are ready ahead.",
                    )
                }
                if (BriefCardType.TOP_FOLLOWER in types) {
                    add("There is a meaningful change in your top followers.")
                }
                when {
                    cards.any { it.type == BriefCardType.STREAK && it.id == "start-streak" } ->
                        add("It may be time to restart your posting rhythm.")
                    BriefCardType.STREAK in types -> add("Your posting rhythm is active.")
                }
                when {
                    BriefCardType.SCHEDULE_GUIDE in types ->
                        add("Your schedule has a useful next step waiting.")
                    BriefCardType.POST_FOLLOW_THROUGH in types ->
                        add("A recently scheduled tweet offers a lesson for your next post.")
                    BriefCardType.POSTING_GUIDE in types ->
                        add("Your recent tweets point to a practical next step.")
                }
            }
            val body = facts.joinToString(" ")
                .ifBlank { "Twidget is watching for your next meaningful account update." }
            return BriefEditorialSummary(title, body)
        }

        private fun followerOverview(today: Long, week: Long): String? = when {
            today > 0L && week >= today ->
                "You gained ${format(today)} followers today and ${format(week)} this week."
            today > 0L -> "You gained ${format(today)} followers today."
            today < 0L && week > 0L ->
                "You are down ${format(-today)} today, but still up ${format(week)} this week."
            week > 0L -> "Your audience grew by ${format(week)} followers this week."
            today < 0L -> "You are down ${format(-today)} followers today."
            week < 0L -> "Your audience is down ${format(-week)} followers this week."
            else -> null
        }

        private fun format(value: Long): String = java.text.NumberFormat
            .getIntegerInstance()
            .format(value)
    }
}

internal object BriefLayoutPolicy {
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600
    const val MAX_CONTENT_WIDTH_DP = 1200

    fun columnCount(screenWidthDp: Int): Int =
        if (screenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) 2 else 1
}

data class BriefSnapshot(
    val username: String,
    val generatedAt: Long,
    val sourceSyncedAt: Long,
    val analyticsCachedAt: Long,
    val followerScanCompletedAt: Long,
    val followers: Long,
    val following: Long,
    val posts: Long,
    val followersToday: Long,
    val followersWeek: Long,
    val cards: List<BriefCard>,
    val upcomingTweets: List<BriefUpcomingTweet> = emptyList(),
    val topFollowerRanks: Map<String, Int>,
    val engineVersion: Int = 0,
    val contextFingerprint: String = "",
    val providerUsed: BriefProviderUsed = BriefProviderUsed.TEMPLATE,
    val providerMessage: String = "Built on device from your Twidget data",
    val aiGeneratedAt: Long = 0L,
)
