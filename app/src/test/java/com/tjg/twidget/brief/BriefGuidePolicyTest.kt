package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefGuidePolicyTest {
    private val now = 1_800_000_000_000L
    private val strings = TestBriefStrings()
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun scheduleHealthPrioritisesAQueuedPostThatNeedsAttention() {
        val card = BriefGuidePolicy.scheduleCard(
            listOf(schedule("failed", ScheduleStatus.FAILED, now + day)),
            strings,
            now,
        )

        assertEquals(BriefCardType.SCHEDULE_GUIDE, card?.type)
        assertEquals(BriefCardAction.OPEN_SCHEDULER, card?.action)
        assertTrue(card?.title.orEmpty().contains("Fix"))
    }

    @Test
    fun scheduleHealthStaysHiddenBeforeSchedulingHasBeenUsed() {
        assertNull(BriefGuidePolicy.scheduleCard(emptyList(), strings, now))
    }

    @Test
    fun scheduleHealthIdentifiesTheDraftUsedForItsPreview() {
        val draft = schedule("draft-preview", ScheduleStatus.DRAFT, now)

        val card = BriefGuidePolicy.scheduleCard(listOf(draft), strings, now)

        assertEquals(draft.id, card?.actionData)
    }

    @Test
    fun followThroughLinksAConservativelyMatchedPublishedTweet() {
        val publishedAt = now - 2 * day
        val text = "A sufficiently distinctive scheduled tweet about making mobile tools easier"
        val post = post(text, publishedAt, views = 2_000, engagements = 80)
        val card = BriefGuidePolicy.followThroughCard(
            schedules = listOf(schedule("published", ScheduleStatus.PUBLISHED, publishedAt, text)),
            analytics = analytics(listOf(post, post("Second mature tweet with enough content", now - 3 * day), post("Third mature tweet with enough content", now - 5 * day / 2))),
            strings = strings,
            now = now,
        )

        assertEquals(BriefCardType.POST_FOLLOW_THROUGH, card?.type)
        assertEquals(BriefCardAction.OPEN_POST, card?.action)
        assertEquals(post.url, card?.actionData)
        assertEquals("Tweeted with Buffer", card?.sourceAttribution)
    }

    @Test
    fun nextPostGuidanceRequiresARepeatableSignal() {
        val posts = listOf(
            post("Visual one has enough useful text", now - 2 * day, 2_000, 60, media = true),
            post("Visual two has enough useful text", now - 3 * day, 1_800, 55, media = true),
            post("Plain one has enough useful text", now - 3 * day, 400, 10),
            post("Plain two has enough useful text", now - 5 * day / 2, 500, 12),
        )

        val card = BriefGuidePolicy.postingCard(analytics(posts), strings, now)

        assertEquals(BriefCardType.POSTING_GUIDE, card?.type)
        assertEquals(BriefCardAction.COMPOSE_TWEET, card?.action)
    }

    @Test
    fun scheduledPostIsNotJudgedAgainstMuchOlderPosts() {
        val publishedAt = now - day
        val text = "A sufficiently distinctive scheduled tweet about making mobile tools easier"
        val posts = listOf(
            post(text, publishedAt, 200, 2),
            post("Older first original post", now - 6 * day, 2_000, 100),
            post("Older second original post", now - 7 * day, 2_000, 100),
        )
        assertNull(BriefGuidePolicy.followThroughCard(
            listOf(schedule("published", ScheduleStatus.PUBLISHED, publishedAt, text)),
            analytics(posts), strings, now,
        ))
    }

    @Test
    fun formatAndTimingAdviceStayHiddenWhenAgeExplainsTheGrouping() {
        val posts = listOf(
            post("Older visual one", now - 6 * day, 2_000, 60, media = true),
            post("Older visual two", now - 7 * day, 1_800, 55, media = true),
            post("Younger plain one", now - day, 400, 10),
            post("Younger plain two", now - 5 * day / 4, 500, 12),
        )
        assertNull(BriefGuidePolicy.postingCard(analytics(posts), strings, now))
    }

    @Test
    fun formatExperimentUsesAgesAndMedianViewsWithEnoughPostsInEachGroup() {
        // No fresh breakout candidate; all four posts are 4–5 days old.
        val posts = listOf(
            post("Visual one", now - 4 * day, 2_000, 60, media = true),
            post("Visual two", now - 5 * day, 1_800, 55, media = true),
            post("Plain one", now - 9 * day / 2, 400, 10),
            post("Plain two", now - 19 * day / 4, 500, 12),
        )
        val card = BriefGuidePolicy.postingCard(analytics(posts), strings, now)
        assertTrue(card?.body.orEmpty().contains("median view count"))
        assertTrue(card?.body.orEmpty().contains("similar age"))
    }

    @Test
    fun cachedNewCountsNeverProduceMaturePostingAdvice() {
        val posts = (1..4).map { index ->
            post("New post $index", now - index * day / 24, index * 1_000L, index * 10L)
        }
        assertNull(BriefGuidePolicy.postingCard(analytics(posts), strings, now + 2 * day))
    }

    private fun schedule(
        id: String,
        status: ScheduleStatus,
        at: Long,
        text: String = "A complete scheduled tweet with enough content",
    ) = ScheduledPost(
        id = id,
        provider = ScheduleProvider.BUFFER,
        status = status,
        accountUsername = "person",
        scheduledAt = at,
        thread = listOf(ScheduleThreadItem(text = text)),
        publishedAt = at.takeIf { status == ScheduleStatus.PUBLISHED },
    )

    private fun analytics(posts: List<PostSummary>) = PostAnalytics(
        userName = "person",
        followers = 1_000,
        postsAnalyzed = posts.size,
        windowDays = 7,
        totalViews = posts.sumOf { it.views },
        avgViews = 1_000.0,
        medianViews = 1_000.0,
        avgViewsPerFollower = 1.0,
        totalEngagements = posts.sumOf { it.engagements },
        avgEngagements = 25.0,
        medianEngagements = 25.0,
        avgEngagementsPerFollower = 0.025,
        engagementRate = 0.025,
        best = posts.maxByOrNull { it.engagements },
        worst = posts.minByOrNull { it.engagements },
        cachedAt = now,
        recentPosts = posts,
    )

    private fun post(
        text: String,
        timestamp: Long,
        views: Long = 1_000,
        engagements: Long = 25,
        media: Boolean = false,
    ) = PostSummary(
        url = "https://x.com/person/status/$timestamp-${text.hashCode()}",
        text = text,
        views = views,
        likes = engagements,
        replies = 0,
        reposts = 0,
        quotes = 0,
        engagements = engagements,
        timestamp = timestamp,
        createdAt = "",
        authorName = "Person",
        authorUserName = "person",
        authorAvatar = "",
        media = if (media) listOf(PostMedia("photo", "https://example.com/$timestamp.jpg", "", 1, 1)) else emptyList(),
    )
}
