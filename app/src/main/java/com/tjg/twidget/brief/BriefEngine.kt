package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollower
import com.tjg.twidget.followers.TopFollowersStore
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

object BriefEngine {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_CARDS = 5

    fun rebuild(context: Context, username: String, force: Boolean = false): BriefSnapshot {
        val clean = username.trim().trimStart('@')
        val stats = TwidgetStore.currentStats(context, clean)
        val analytics = AnalyticsClient.cached(context, clean)
        val followerState = TopFollowersStore.read(context, clean)
        val previous = BriefStore.read(context, clean)
        if (!force && previous != null &&
            previous.sourceSyncedAt == stats.syncedAt &&
            previous.analyticsCachedAt == (analytics?.cachedAt ?: 0L) &&
            previous.followerScanCompletedAt == followerState.completedAt
        ) return previous

        val history = TwidgetStore.fullHistory(context, clean)
            .filterNot { it.estimated }
            .sortedBy { it.timestamp }
        val todayDelta = TwidgetStore.followersDelta(context, clean)
        val weekDelta = deltaSince(history, stats.followersCount, 7, HistorySample::followers)
        val currentRanks = followerState.top.take(100).mapIndexed { index, follower ->
            followerKey(follower) to index + 1
        }.toMap()
        val facts = mutableListOf<BriefCard>()

        growthCard(stats.followersCount, todayDelta, weekDelta)?.let(facts::add)
        postCard(analytics?.best, stats.followersCount)?.let(facts::add)
        slowdownCard(history, stats.followersCount, todayDelta)?.let(facts::add)
        inactivityCard(history, stats.statusesCount)?.let(facts::add)
        milestoneCard(context, clean, stats.followersCount)?.let(facts::add)
        streakCard(context, clean)?.let(facts::add)
        topFollowerCard(followerState.top, previous, followerState.completedAt)?.let(facts::add)

        if (facts.isEmpty()) {
            facts += BriefCard(
                id = "summary-steady",
                type = BriefCardType.SUMMARY,
                title = "Everything looks steady",
                body = "You have ${format(stats.followersCount)} followers. Keep showing up and Twidget will watch for the next meaningful change.",
                score = 50,
            )
        }

        val snapshot = BriefSnapshot(
            username = clean,
            generatedAt = System.currentTimeMillis(),
            sourceSyncedAt = stats.syncedAt,
            analyticsCachedAt = analytics?.cachedAt ?: 0L,
            followerScanCompletedAt = followerState.completedAt,
            followers = stats.followersCount,
            following = stats.followingsCount,
            posts = stats.statusesCount,
            followersToday = todayDelta,
            followersWeek = weekDelta,
            cards = facts.sortedByDescending(BriefCard::score).take(MAX_CARDS),
            topFollowerRanks = currentRanks,
        )
        BriefStore.write(context, snapshot)
        return snapshot
    }

    private fun growthCard(followers: Long, today: Long, week: Long): BriefCard? {
        val weeklyPercent = if (followers - week > 0) week * 100.0 / (followers - week) else 0.0
        if (today < 5 && week < 15 && weeklyPercent < 2.0) return null
        val headline = when {
            today >= 25 -> "You’re getting attention"
            weeklyPercent >= 5.0 -> "Your audience is taking off"
            else -> "Momentum is building"
        }
        val body = when {
            today > 0 -> "You gained ${format(today)} followers today and ${format(week.coerceAtLeast(today))} over the last week."
            else -> "You gained ${format(week)} followers over the last week."
        }
        return BriefCard("growth", BriefCardType.GROWTH, headline, body, 95)
    }

    private fun postCard(post: PostSummary?, followers: Long): BriefCard? {
        post ?: return null
        val attentionByViews = post.views >= maxOf(10_000L, followers * 2)
        val attentionByLikes = post.likes >= maxOf(100L, followers / 50)
        if (!attentionByViews && !attentionByLikes) return null
        return BriefCard(
            id = "post-${post.timestamp.takeIf { it > 0L } ?: post.url.hashCode()}",
            type = BriefCardType.POST,
            title = "Getting attention",
            body = standoutPostBody(post),
            score = 98,
        )
    }

    private fun standoutPostBody(post: PostSummary): String {
        val views = TwidgetStore.compactNumber(post.views)
        val likes = TwidgetStore.compactNumber(post.likes)
        return when {
            post.views > 0 && post.likes > 0 -> "Your last post got $views views and $likes likes."
            post.views > 0 -> "Your last post got $views views."
            else -> "Your last post got $likes likes."
        }
    }

    private fun slowdownCard(history: List<HistorySample>, followers: Long, today: Long): BriefCard? {
        val recent = deltaSince(history, followers, 3, HistorySample::followers)
        val priorEnd = history.lastOrNull { it.timestamp <= System.currentTimeMillis() - 3 * DAY_MS } ?: return null
        val prior = deltaSince(history.filter { it.timestamp <= priorEnd.timestamp }, priorEnd.followers, 3, HistorySample::followers)
        if (today >= 0 && (prior <= 5 || recent >= prior / 2)) return null
        return BriefCard(
            "slowdown",
            BriefCardType.SLOWDOWN,
            "Growth has slowed down",
            "Your recent pace is ${format(abs(prior - recent))} followers behind the previous few days. A fresh post could help restart it.",
            82,
        )
    }

    private fun inactivityCard(history: List<HistorySample>, posts: Long): BriefCard? {
        val lastPostChange = history.zipWithNext()
            .lastOrNull { (before, after) -> after.postsKnown && before.postsKnown && after.posts > before.posts }
            ?.second?.timestamp
            ?: history.lastOrNull { it.postsKnown && it.posts < posts }?.timestamp
            ?: return null
        val days = ((System.currentTimeMillis() - lastPostChange) / DAY_MS).toInt()
        if (days < 3) return null
        return BriefCard(
            "inactivity",
            BriefCardType.INACTIVITY,
            "Ready for your next post?",
            "It’s been about $days days since Twidget saw new activity. Even a quick update keeps your rhythm alive.",
            (72 + days).coerceAtMost(88),
        )
    }

    private fun milestoneCard(context: Context, username: String, followers: Long): BriefCard? {
        val target = TwidgetStore.milestoneSettings(context, username).target ?: return null
        if (target <= 0L) return null
        val remaining = target - followers
        return when {
            remaining <= 0 -> BriefCard(
                "milestone-$target",
                BriefCardType.MILESTONE,
                "Milestone reached!",
                "You made it to ${format(target)} followers. That deserves a victory lap.",
                100,
            )
            remaining <= (target * 0.1).coerceAtLeast(10.0) -> BriefCard(
                "milestone-$target",
                BriefCardType.MILESTONE,
                "Your goal is close",
                "Only ${format(remaining)} followers remain before you reach ${format(target)}.",
                90,
            )
            else -> null
        }
    }

    private fun streakCard(context: Context, username: String): BriefCard? {
        val streak = DailyStreakStore.snapshot(context, username)
        if (streak.streak < 3) return null
        return BriefCard(
            "streak",
            BriefCardType.STREAK,
            "${streak.streak}-day posting streak",
            if (streak.activeToday) "You’ve already kept the streak alive today. Nice work."
            else "Post today to keep your ${streak.streak}-day rhythm going.",
            if (streak.activeToday) 70 else 84,
        )
    }

    private fun topFollowerCard(
        top: List<TopFollower>,
        previous: BriefSnapshot?,
        completedAt: Long,
    ): BriefCard? {
        val follower = top.firstOrNull() ?: return null
        val oldRank = previous?.topFollowerRanks?.get(followerKey(follower))
        val newScan = previous != null && completedAt > previous.followerScanCompletedAt
        val title = when {
            newScan && oldRank == null -> "A new top follower"
            oldRank != null && oldRank > 1 -> "A follower moved up"
            previous == null -> "Your top follower"
            else -> return null
        }
        val movement = oldRank?.takeIf { it > 1 }?.let { " They moved from #$it to #1." }.orEmpty()
        return BriefCard(
            "top-follower-${followerKey(follower)}",
            BriefCardType.TOP_FOLLOWER,
            title,
            "${follower.name.ifBlank { "@${follower.username}" }} has ${format(follower.followers)} followers.$movement",
            if (newScan && oldRank == null) 92 else 76,
        )
    }

    private fun deltaSince(
        history: List<HistorySample>,
        current: Long,
        days: Int,
        selector: (HistorySample) -> Long,
    ): Long {
        val threshold = System.currentTimeMillis() - days * DAY_MS
        val baseline = history.lastOrNull { it.timestamp <= threshold } ?: history.firstOrNull() ?: return 0L
        return current - selector(baseline)
    }

    private fun followerKey(follower: TopFollower): String =
        follower.id.ifBlank { follower.username.lowercase(Locale.US) }

    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)
}
