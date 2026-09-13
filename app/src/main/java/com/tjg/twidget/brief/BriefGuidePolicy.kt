package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduledPost
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Evidence thresholds for advice shown by the Brief. */
internal object BriefGuidePolicy {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MATCH_WINDOW_MS = 3 * DAY_MS
    private const val FOLLOW_UP_WINDOW_MS = 3 * DAY_MS

    fun scheduleCard(
        posts: List<ScheduledPost>,
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefCard? {
        val active = posts.filter { it.deletedAt == null }
        val needsAttention = active.firstOrNull {
            it.status == ScheduleStatus.FAILED || it.status == ScheduleStatus.NEEDS_ACTION
        }
        if (needsAttention != null) {
            return BriefCard(
                id = "schedule-attention-${needsAttention.id}",
                type = BriefCardType.SCHEDULE_GUIDE,
                title = strings.text(R.string.brief_guide_fix_title),
                body = strings.text(R.string.brief_guide_fix_body),
                score = 97,
                action = BriefCardAction.OPEN_SCHEDULER,
                rankSignals = BriefRankSignals(contextRelevance = 1.0, timeRelevance = 1.0),
            )
        }

        val readyDrafts = active.filter { post ->
            post.status == ScheduleStatus.DRAFT && post.thread.any { it.text.isNotBlank() || it.media.isNotEmpty() }
        }
        val previewDraft = readyDrafts.firstOrNull()
        if (previewDraft != null) {
            return BriefCard(
                id = "schedule-drafts-${readyDrafts.size}",
                type = BriefCardType.SCHEDULE_GUIDE,
                title = strings.text(
                    if (readyDrafts.size == 1) R.string.brief_guide_draft_title_one else R.string.brief_guide_draft_title_many,
                ),
                body = if (readyDrafts.size == 1) {
                    strings.text(R.string.brief_guide_draft_body_one)
                } else {
                    strings.text(R.string.brief_guide_draft_body_many, readyDrafts.size)
                },
                score = 87,
                action = BriefCardAction.OPEN_SCHEDULER,
                actionData = previewDraft.id,
                rankSignals = BriefRankSignals(contextRelevance = 0.88, timeRelevance = 0.75),
            )
        }

        // Avoid advertising scheduling to someone who has never used it. Once
        // it is part of their workflow, a genuine gap becomes useful guidance.
        if (active.none { it.status != ScheduleStatus.CANCELLED }) return null
        val next = active.asSequence()
            .filter { it.status == ScheduleStatus.SCHEDULED }
            .mapNotNull(ScheduledPost::scheduledAt)
            .filter { it > now }
            .minOrNull()
        if (next != null && next - now <= 3 * DAY_MS) return null
        return BriefCard(
            id = "schedule-gap-${now / DAY_MS}",
            type = BriefCardType.SCHEDULE_GUIDE,
            title = strings.text(R.string.brief_guide_gap_title),
            body = strings.text(
                if (next == null) R.string.brief_guide_gap_body_none else R.string.brief_guide_gap_body_far,
            ),
            score = 82,
            action = BriefCardAction.COMPOSE_TWEET,
            rankSignals = BriefRankSignals(contextRelevance = 0.72, timeRelevance = 0.65),
        )
    }

    fun followThroughCard(
        schedules: List<ScheduledPost>,
        analytics: PostAnalytics?,
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefCard? {
        analytics ?: return null
        if (analytics.recentPosts.size < 3) return null
        val matched = schedules.asSequence()
            .filter { it.status == ScheduleStatus.PUBLISHED }
            .mapNotNull { schedule ->
                val publishedAt = schedule.publishedAt ?: schedule.scheduledAt ?: return@mapNotNull null
                if (now - publishedAt !in DAY_MS..(analytics.windowDays * DAY_MS)) return@mapNotNull null
                val scheduledText = schedule.thread.firstOrNull()?.text.orEmpty()
                analytics.recentPosts
                    .filter { post -> timestampsMatch(publishedAt, post.timestamp) && textMatches(scheduledText, post.text) }
                    .minByOrNull { post -> kotlin.math.abs(post.timestamp - publishedAt) }
                    ?.let { schedule to it }
            }
            .maxByOrNull { (_, post) -> post.timestamp }
            ?: return null
        val (schedule, match) = matched

        val viewRatio = ratio(match.views.toDouble(), analytics.medianViews)
        val engagementRatio = ratio(match.engagements.toDouble(), analytics.medianEngagements)
        val strong = maxOf(viewRatio, engagementRatio) >= 1.35
        val quiet = viewRatio <= 0.7 && engagementRatio <= 0.8
        if (!strong && !quiet) return null
        return BriefCard(
            id = "follow-through-${match.timestamp.takeIf { it > 0L } ?: match.url.hashCode()}",
            type = BriefCardType.POST_FOLLOW_THROUGH,
            title = strings.text(
                if (strong) R.string.brief_guide_follow_through_strong_title else R.string.brief_guide_follow_through_quiet_title,
            ),
            body = strings.text(
                if (strong) R.string.brief_guide_follow_through_strong_body else R.string.brief_guide_follow_through_quiet_body,
            ),
            score = if (strong) 94 else 88,
            action = BriefCardAction.OPEN_POST,
            actionData = match.url,
            sourceAttribution = strings.text(
                when (schedule.provider) {
                    com.tjg.twidget.schedule.ScheduleProvider.BUFFER -> R.string.brief_guide_source_buffer
                    com.tjg.twidget.schedule.ScheduleProvider.LOCAL_REMINDER -> R.string.brief_guide_source_twidget
                },
            ),
            rankSignals = BriefRankSignals(
                contextRelevance = 0.9,
                timeRelevance = 0.35,
                occurredAt = match.timestamp,
                freshForMillis = FOLLOW_UP_WINDOW_MS,
                validUntil = match.timestamp + analytics.windowDays * DAY_MS,
            ),
        )
    }

    fun postingCard(
        analytics: PostAnalytics?,
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefCard? {
        analytics ?: return null
        val posts = analytics.recentPosts.filter {
            it.timestamp > 0L && now - it.timestamp in DAY_MS..(analytics.windowDays * DAY_MS)
        }
        if (posts.size < 4) return null
        followUpCard(posts, analytics, now, strings)?.let { return it }
        postingWindowCard(posts, strings)?.let { return it }
        return formatExperimentCard(posts, strings)
    }

    private fun followUpCard(
        posts: List<PostSummary>,
        analytics: PostAnalytics,
        now: Long,
        strings: BriefStrings,
    ): BriefCard? {
        val post = posts.filter { now - it.timestamp in DAY_MS..FOLLOW_UP_WINDOW_MS }
            .maxByOrNull { performance(it) } ?: return null
        val performanceRatio = maxOf(
            ratio(post.views.toDouble(), analytics.medianViews),
            ratio(post.engagements.toDouble(), analytics.medianEngagements),
        )
        if (performanceRatio < 1.6) return null
        return BriefCard(
            id = "posting-follow-up-${post.timestamp}",
            type = BriefCardType.POSTING_GUIDE,
            title = strings.text(R.string.brief_guide_follow_up_title),
            body = strings.text(R.string.brief_guide_follow_up_body),
            score = 93,
            action = BriefCardAction.COMPOSE_TWEET,
            rankSignals = BriefRankSignals(
                contextRelevance = 0.95,
                timeRelevance = 0.4,
                occurredAt = post.timestamp,
                freshForMillis = FOLLOW_UP_WINDOW_MS,
                validUntil = post.timestamp + FOLLOW_UP_WINDOW_MS,
            ),
        )
    }

    private enum class PostingWindow(val id: String, val labelRes: Int) {
        MORNING("morning", R.string.brief_window_morning),
        AFTERNOON("afternoon", R.string.brief_window_afternoon),
        EVENING("evening", R.string.brief_window_evening),
        LATE_NIGHT("late-night", R.string.brief_window_late_night),
    }

    private fun postingWindowCard(posts: List<PostSummary>, strings: BriefStrings): BriefCard? {
        val groups = posts.groupBy { post ->
            val hour = Instant.ofEpochMilli(post.timestamp).atZone(ZoneId.systemDefault()).hour
            when (hour) {
                in 5..11 -> PostingWindow.MORNING
                in 12..16 -> PostingWindow.AFTERNOON
                in 17..22 -> PostingWindow.EVENING
                else -> PostingWindow.LATE_NIGHT
            }
        }.filterValues { it.size >= 2 }
        if (groups.size < 2) return null
        val averages = groups.mapValues { (_, values) -> values.map(::performance).average() }
        val winner = averages.maxByOrNull(Map.Entry<PostingWindow, Double>::value) ?: return null
        val others = averages.filterKeys { it != winner.key }.values.average()
        if (others <= 0.0 || winner.value / others < 1.35) return null
        val label = strings.text(winner.key.labelRes)
        return BriefCard(
            id = "posting-window-${winner.key.id}",
            type = BriefCardType.POSTING_GUIDE,
            title = strings.text(R.string.brief_guide_window_title, label),
            body = strings.text(R.string.brief_guide_window_body, label),
            score = 86,
            action = BriefCardAction.COMPOSE_TWEET,
            rankSignals = BriefRankSignals(contextRelevance = 0.82, timeRelevance = 0.7),
        )
    }

    private fun formatExperimentCard(posts: List<PostSummary>, strings: BriefStrings): BriefCard? {
        val withMedia = posts.filter { it.media.isNotEmpty() }
        val textOnly = posts.filter { it.media.isEmpty() }
        if (withMedia.size < 2 || textOnly.size < 2) return null
        val mediaAverage = withMedia.map(::performance).average()
        val textAverage = textOnly.map(::performance).average()
        if (mediaAverage <= 0.0 || textAverage <= 0.0) return null
        val mediaWins = mediaAverage > textAverage
        val ratio = if (mediaWins) mediaAverage / textAverage else textAverage / mediaAverage
        if (ratio < 1.5) return null
        return BriefCard(
            id = "posting-format-${if (mediaWins) "media" else "text"}",
            type = BriefCardType.POSTING_GUIDE,
            title = strings.text(
                if (mediaWins) R.string.brief_guide_format_media_title else R.string.brief_guide_format_text_title,
            ),
            body = strings.text(
                if (mediaWins) R.string.brief_guide_format_media_body else R.string.brief_guide_format_text_body,
            ),
            score = 84,
            action = BriefCardAction.COMPOSE_TWEET,
            rankSignals = BriefRankSignals(contextRelevance = 0.78, timeRelevance = 0.65),
        )
    }

    private fun performance(post: PostSummary): Double = when {
        post.views > 0L -> post.views.toDouble()
        else -> post.engagements.toDouble()
    }

    private fun timestampsMatch(left: Long, right: Long): Boolean =
        left > 0L && right > 0L && kotlin.math.abs(left - right) <= MATCH_WINDOW_MS

    private fun textMatches(left: String, right: String): Boolean {
        val a = normalizeText(left)
        val b = normalizeText(right)
        if (a.length < 20 || b.length < 20) return false
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    private fun normalizeText(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun ratio(value: Double, baseline: Double): Double =
        if (baseline > 0.0 && baseline.isFinite()) value / baseline else 1.0
}
