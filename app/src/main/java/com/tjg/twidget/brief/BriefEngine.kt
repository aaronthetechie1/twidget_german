package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollower
import com.tjg.twidget.followers.TopFollowersState
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.main.MilestoneCopyFactory
import com.tjg.twidget.main.MilestoneGoalStore
import com.tjg.twidget.main.MilestoneMetric
import com.tjg.twidget.main.MilestoneMetricResolver
import com.tjg.twidget.main.MilestonePerformanceState
import com.tjg.twidget.main.MilestonePolicy
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

object BriefEngine {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_CARDS = 5
    private const val ENGINE_VERSION = 2

    fun rebuild(context: Context, username: String, force: Boolean = false): BriefSnapshot {
        val clean = username.trim().trimStart('@')
        val stats = TwidgetStore.currentStats(context, clean)
        val analytics = AnalyticsClient.cached(context, clean)
        val followerState = TopFollowersStore.read(context, clean)
        val previous = BriefStore.read(context, clean)
        val fingerprint = contextFingerprint(context, clean)
        if (!force && previous != null &&
            previous.engineVersion == ENGINE_VERSION &&
            previous.contextFingerprint == fingerprint &&
            previous.username.equals(clean, ignoreCase = true) &&
            previous.sourceSyncedAt == stats.syncedAt &&
            previous.analyticsCachedAt == (analytics?.cachedAt ?: 0L) &&
            previous.followerScanCompletedAt == followerState.completedAt
        ) {
            if (TwidgetStore.debugMenuUnlocked(context)) {
                BriefDebugLog.record(context, "cache hit", inspect(context, clean))
            }
            return previous
        }

        val evaluation = evaluate(context, clean, stats, analytics, followerState, previous)

        val snapshot = BriefSnapshot(
            username = clean,
            generatedAt = System.currentTimeMillis(),
            sourceSyncedAt = stats.syncedAt,
            analyticsCachedAt = analytics?.cachedAt ?: 0L,
            followerScanCompletedAt = followerState.completedAt,
            followers = stats.followersCount,
            following = stats.followingsCount,
            posts = stats.statusesCount,
            followersToday = evaluation.report.followersToday,
            followersWeek = evaluation.report.followersWeek,
            cards = evaluation.selected,
            topFollowerRanks = evaluation.currentRanks,
            engineVersion = ENGINE_VERSION,
            contextFingerprint = fingerprint,
        )
        BriefStore.write(context, snapshot)
        BriefDebugLog.record(context, if (force) "forced rebuild" else "rebuild", evaluation.report)
        return snapshot
    }

    fun inspect(context: Context, username: String): BriefEngineReport {
        val clean = username.trim().trimStart('@')
        return evaluate(
            context = context,
            username = clean,
            stats = TwidgetStore.currentStats(context, clean),
            analytics = AnalyticsClient.cached(context, clean),
            followerState = TopFollowersStore.read(context, clean),
            previous = BriefStore.read(context, clean),
        ).report
    }

    private data class Evaluation(
        val report: BriefEngineReport,
        val selected: List<BriefCard>,
        val currentRanks: Map<String, Int>,
    )

    private fun evaluate(
        context: Context,
        username: String,
        stats: ProfileStats,
        analytics: PostAnalytics?,
        followerState: TopFollowersState,
        previous: BriefSnapshot?,
    ): Evaluation {
        val history = TwidgetStore.fullHistory(context, username)
            .filterNot { it.estimated }
            .sortedBy { it.timestamp }
        val todayDelta = TwidgetStore.followersDelta(context, username)
        val weekDelta = deltaSince(history, stats.followersCount, 7, HistorySample::followers)
        val currentRanks = followerState.top.take(100).mapIndexed { index, follower ->
            followerKey(follower) to index + 1
        }.toMap()
        val candidates = mutableListOf<BriefCard>()

        growthCard(stats.followersCount, todayDelta, weekDelta)?.let(candidates::add)
        postCard(analytics?.best, stats.followersCount)?.let(candidates::add)
        slowdownCard(history, stats.followersCount, todayDelta)?.let(candidates::add)
        inactivityCard(history, stats.statusesCount)?.let(candidates::add)
        milestoneCard(context, username, stats, history, analytics)?.let(candidates::add)
        streakCard(context, username)?.let(candidates::add)
        topFollowerCard(followerState.top, previous, followerState.completedAt)?.let(candidates::add)

        if (candidates.isEmpty()) {
            candidates += BriefCard(
                id = "summary-steady",
                type = BriefCardType.SUMMARY,
                title = "Everything looks steady",
                body = "You have ${format(stats.followersCount)} followers. Keep showing up and Twidget will watch for the next meaningful change.",
                score = 50,
            )
        }
        val ranked = BriefRankingPolicy.order(candidates)
        val selected = ranked.take(MAX_CARDS)
        return Evaluation(
            report = BriefEngineReport(
                username = username,
                generatedAt = System.currentTimeMillis(),
                followers = stats.followersCount,
                following = stats.followingsCount,
                posts = stats.statusesCount,
                followersToday = todayDelta,
                followersWeek = weekDelta,
                historySamples = history.size,
                analyticsCachedAt = analytics?.cachedAt ?: 0L,
                standoutPostViews = analytics?.best?.views,
                followerScanCompletedAt = followerState.completedAt,
                followersScanned = followerState.scanned,
                rankedCandidates = ranked,
                selectedIds = selected.mapTo(linkedSetOf(), BriefCard::id),
            ),
            selected = selected,
            currentRanks = currentRanks,
        )
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
        val score = BriefRankingPolicy.growth(today, week, weeklyPercent)
        return BriefCard("growth", BriefCardType.GROWTH, headline, body, score)
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
            score = BriefRankingPolicy.post(post, followers),
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
            BriefRankingPolicy.slowdown(recent, prior, today),
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

    private fun milestoneCard(
        context: Context,
        username: String,
        stats: ProfileStats,
        history: List<HistorySample>,
        analytics: PostAnalytics?,
    ): BriefCard? {
        val settings = MilestoneGoalStore.read(context, username)
        if (!settings.configured || settings.target <= 0.0) return null
        val snapshot = MilestoneMetricResolver.resolve(
            context = context,
            account = username,
            metric = settings.metric,
            stats = stats,
            history = history,
            analytics = analytics,
            imported = ImportedAnalyticsStore.all(context, username),
        )
        val value = snapshot.value ?: return null
        val progress = MilestonePolicy.progress(value, settings.target) ?: return null
        val state = MilestonePolicy.performanceState(snapshot.history)
        val target = formatGoal(settings.metric, settings.target)
        val message = MilestoneCopyFactory.message(
            context,
            username,
            state,
            progress,
            target,
            goalNoun(settings.metric),
        )
        return BriefCard(
            id = "milestone-${settings.metric.storageId}-${settings.target}",
            type = BriefCardType.MILESTONE,
            title = message.title,
            body = message.body,
            score = BriefRankingPolicy.milestone(progress, state),
        )
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
            BriefRankingPolicy.streak(streak.streak, streak.activeToday),
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

    private fun contextFingerprint(context: Context, username: String): String {
        val goal = MilestoneGoalStore.read(context, username)
        val streak = DailyStreakStore.snapshot(context, username)
        return listOf(
            goal.configured,
            goal.metric.storageId,
            goal.target,
            goal.autoAdjust,
            streak.streak,
            streak.activeToday,
            streak.lastActiveDay,
        ).joinToString("|")
    }

    private fun formatGoal(metric: MilestoneMetric, target: Double): String =
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(target * 100).toInt()}%"
        } else {
            format(target.toLong())
        }

    private fun goalNoun(metric: MilestoneMetric): String = when (metric) {
        MilestoneMetric.FOLLOWERS -> "follower"
        MilestoneMetric.VERIFIED_FOLLOWERS -> "verified follower"
        MilestoneMetric.ENGAGEMENT_RATE -> "engagement rate"
        MilestoneMetric.IMPRESSIONS -> "impression"
    }

    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)
}

internal object BriefRankingPolicy {
    fun order(cards: List<BriefCard>): List<BriefCard> = cards.sortedWith(
        compareByDescending<BriefCard>(BriefCard::score).thenBy(BriefCard::id),
    )

    fun growth(today: Long, week: Long, weeklyPercent: Double): Int =
        (68 + today.coerceAtLeast(0).coerceAtMost(25) +
            (week.coerceAtLeast(0).coerceAtMost(50) / 5) +
            weeklyPercent.coerceIn(0.0, 10.0).toInt()).coerceAtMost(99).toInt()

    fun post(post: PostSummary, followers: Long): Int {
        val viewThreshold = maxOf(10_000L, followers * 2).coerceAtLeast(1L)
        val likeThreshold = maxOf(100L, followers / 50).coerceAtLeast(1L)
        val views = (post.views.toDouble() / viewThreshold).coerceIn(0.0, 4.0)
        val likes = (post.likes.toDouble() / likeThreshold).coerceIn(0.0, 4.0)
        return (72 + maxOf(views, likes) * 6).toInt().coerceAtMost(98)
    }

    fun slowdown(recent: Long, prior: Long, today: Long): Int {
        val lostPace = (prior - recent).coerceAtLeast(0)
        return (68 + lostPace.coerceAtMost(20) + if (today < 0) 8 else 0)
            .coerceAtMost(96)
            .toInt()
    }

    fun milestone(progress: Int, state: MilestonePerformanceState): Int {
        val stateBoost = when (state) {
            MilestonePerformanceState.ACCELERATING -> 3
            MilestonePerformanceState.DECELERATING -> 7
            MilestonePerformanceState.NEUTRAL -> 0
        }
        return (52 + progress / 2 + stateBoost).coerceAtMost(100)
    }

    fun streak(days: Int, activeToday: Boolean): Int =
        (if (activeToday) 58 else 76) + days.coerceAtMost(20) / 2
}
