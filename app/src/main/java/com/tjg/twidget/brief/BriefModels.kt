package com.tjg.twidget.brief

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
    val topFollowerRanks: Map<String, Int>,
    val engineVersion: Int = 0,
    val contextFingerprint: String = "",
    val providerUsed: BriefProviderUsed = BriefProviderUsed.TEMPLATE,
    val providerMessage: String = "Built on device from your Twidget data",
)
