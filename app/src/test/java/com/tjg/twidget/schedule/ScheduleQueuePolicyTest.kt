package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleQueuePolicyTest {
    @Test
    fun localQueueOnlyIncludesDefaultAccountLocalDrafts() {
        val defaultLocal = post(ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", "thatjoshguy69")
        val otherLocal = post(ScheduleProvider.LOCAL_REMINDER, "kingowenfyi", "kingowenfyi")
        val bufferPost = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")

        assertTrue(ScheduleQueuePolicy.includes(defaultLocal, ScheduleProvider.LOCAL_REMINDER, "@thatjoshguy69", null))
        assertFalse(ScheduleQueuePolicy.includes(otherLocal, ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", null))
        assertFalse(ScheduleQueuePolicy.includes(bufferPost, ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", null))
    }

    @Test
    fun bufferQueueUsesTheMappedBufferChannel() {
        val selectedChannel = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
        val otherChannel = post(ScheduleProvider.BUFFER, "thatjoshguy69", "other-channel")
        val localPost = post(ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", "thatjoshguy69")

        assertTrue(ScheduleQueuePolicy.includes(selectedChannel, ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel"))
        assertFalse(ScheduleQueuePolicy.includes(otherChannel, ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel"))
        assertFalse(ScheduleQueuePolicy.includes(localPost, ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel"))
    }

    @Test
    fun publishedNotificationOnlyFiresOnScheduledBufferTransition() {
        val scheduled = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
            .copy(status = ScheduleStatus.SCHEDULED)
        val draft = scheduled.copy(status = ScheduleStatus.DRAFT)
        val local = scheduled.copy(provider = ScheduleProvider.LOCAL_REMINDER)

        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferPublished(scheduled, ScheduleStatus.PUBLISHED))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(draft, ScheduleStatus.PUBLISHED))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(local, ScheduleStatus.PUBLISHED))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(scheduled, ScheduleStatus.SCHEDULED))
    }

    @Test
    fun failedNotificationOnlyFiresOnScheduledBufferTransition() {
        val scheduled = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
            .copy(status = ScheduleStatus.SCHEDULED)
        val alreadyFailed = scheduled.copy(status = ScheduleStatus.NEEDS_ACTION)
        val presumedPublished = scheduled.copy(status = ScheduleStatus.PUBLISHED)
        val local = scheduled.copy(provider = ScheduleProvider.LOCAL_REMINDER)

        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferFailed(scheduled, ScheduleStatus.NEEDS_ACTION))
        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferFailed(presumedPublished, ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(alreadyFailed, ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(local, ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(scheduled, ScheduleStatus.PUBLISHED))
    }

    @Test
    fun cardMediaKeepsTheFirstFourAttachmentsAcrossAThread() {
        val media = (1..6).map { PublicUrlMedia("https://example.com/$it.jpg") }
        val post = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel").copy(
            thread = listOf(
                ScheduleThreadItem(text = "First", media = media.take(2)),
                ScheduleThreadItem(text = "Second", media = media.drop(2)),
            ),
        )

        assertEquals(media.take(4), ScheduleQueuePolicy.cardMedia(post))
    }

    @Test
    fun queueOrdersAttentionThenUpcomingThenDraftsThenCompletedPosts() {
        val base = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
        val posts = listOf(
            base.copy(id = "old-published", status = ScheduleStatus.PUBLISHED, publishedAt = 10L),
            base.copy(id = "draft", status = ScheduleStatus.DRAFT, updatedAt = 40L),
            base.copy(id = "later", status = ScheduleStatus.SCHEDULED, scheduledAt = 30L),
            base.copy(id = "failed", status = ScheduleStatus.NEEDS_ACTION, updatedAt = 20L),
            base.copy(id = "recent-published", status = ScheduleStatus.PUBLISHED, publishedAt = 50L),
            base.copy(id = "sooner", status = ScheduleStatus.SCHEDULED, scheduledAt = 20L),
        )

        assertEquals(
            listOf("failed", "sooner", "later", "draft", "recent-published", "old-published"),
            ScheduleQueuePolicy.order(posts).map(ScheduledPost::id),
        )
    }

    private fun post(provider: ScheduleProvider, accountId: String, accountUsername: String) = ScheduledPost(
        provider = provider,
        accountId = accountId,
        accountUsername = accountUsername,
        scheduledAt = 1L,
        thread = listOf(ScheduleThreadItem(text = "Draft")),
    )
}
