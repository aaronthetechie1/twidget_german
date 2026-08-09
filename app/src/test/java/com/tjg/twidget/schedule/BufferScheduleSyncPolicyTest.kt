package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferScheduleSyncPolicyTest {
    private val now = 1_000_000L

    @Test
    fun dueScheduledPostIsRetainedUntilBufferReportsTerminalStatus() {
        val post = scheduledPost(scheduledAt = now - 1_000L)

        assertFalse(BufferScheduleSync.shouldRemoveMissing(post, emptySet(), now))
        assertTrue(BufferScheduleSync.shouldAssumePublished(post, emptySet(), now))
    }

    @Test
    fun nearDueScheduledPostIsRetainedAcrossBufferConsistencyWindow() {
        val post = scheduledPost(scheduledAt = now + 60_000L)

        assertFalse(BufferScheduleSync.shouldRemoveMissing(post, emptySet(), now))
    }

    @Test
    fun genuinelyRemovedFutureScheduleIsRemovedLocally() {
        val post = scheduledPost(scheduledAt = now + 10 * 60_000L)

        assertTrue(BufferScheduleSync.shouldRemoveMissing(post, emptySet(), now))
    }

    @Test
    fun remotePostStillReturnedByBufferIsNeverRemoved() {
        val post = scheduledPost(scheduledAt = now + 10 * 60_000L)

        assertFalse(BufferScheduleSync.shouldRemoveMissing(post, setOf("remote-1"), now))
        assertFalse(BufferScheduleSync.shouldAssumePublished(post, setOf("remote-1"), now))
    }

    @Test
    fun overdueScheduledAndSendingStatusesFallBackToPublished() {
        assertEquals(
            ScheduleStatus.PUBLISHED,
            BufferScheduleSync.resolvedStatus("scheduled", now - 1L, now),
        )
        assertEquals(
            ScheduleStatus.PUBLISHED,
            BufferScheduleSync.resolvedStatus("sending", now, now),
        )
    }

    @Test
    fun explicitBufferErrorOverridesTheDueTimeFallback() {
        assertEquals(
            ScheduleStatus.NEEDS_ACTION,
            BufferScheduleSync.resolvedStatus("error", now - 1L, now),
        )
    }

    @Test
    fun futureScheduledPostRemainsUpcoming() {
        assertEquals(
            ScheduleStatus.SCHEDULED,
            BufferScheduleSync.resolvedStatus("scheduled", now + 1L, now),
        )
        assertFalse(BufferScheduleSync.shouldAssumePublished(
            scheduledPost(scheduledAt = now + 1L),
            emptySet(),
            now,
        ))
    }

    @Test
    fun recentlyPresumedPostKeepsCheckingForAnExplicitTerminalStatus() {
        val post = scheduledPost(scheduledAt = now - 1_000L).copy(
            status = ScheduleStatus.PUBLISHED,
            publishedAt = now - 1_000L,
        )

        assertEquals(now - 1_000L, BufferScheduleSync.terminalConfirmationTime(post, now))
        assertEquals(
            null,
            BufferScheduleSync.terminalConfirmationTime(
                post.copy(publishedAt = now - 24 * 60 * 60 * 1000L - 1L),
                now,
            ),
        )
    }

    private fun scheduledPost(scheduledAt: Long) = ScheduledPost(
        provider = ScheduleProvider.BUFFER,
        status = ScheduleStatus.SCHEDULED,
        accountUsername = "buffer-channel",
        scheduledAt = scheduledAt,
        thread = listOf(ScheduleThreadItem(text = "Scheduled post")),
        remotePostId = "remote-1",
    )
}
