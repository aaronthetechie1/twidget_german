package com.tjg.twidget.brief

import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus

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
}

data class BriefCard(
    val id: String,
    val type: BriefCardType,
    val title: String,
    val body: String,
    val score: Int,
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
        fun from(cards: List<BriefCard>): BriefEditorialSummary {
            val lead = cards.firstOrNull()
            val title = lead?.title.orEmpty().ifBlank { "Your Twidget Brief" }
            val body = cards.asSequence()
                .map(BriefCard::body)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(3)
                .joinToString(" ")
                .ifBlank { "Twidget is watching for your next meaningful account update." }
            return BriefEditorialSummary(title, body)
        }
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
