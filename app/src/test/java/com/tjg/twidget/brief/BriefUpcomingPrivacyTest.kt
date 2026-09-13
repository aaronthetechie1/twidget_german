package com.tjg.twidget.brief

import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BriefUpcomingPrivacyTest {
    private val strings = TestBriefStrings()

    @Test
    fun aiPromptsNeverContainUnpublishedScheduledCopy() {
        val secret = "unpublished launch announcement"
        val snapshot = BriefSnapshot(
            username = "person",
            generatedAt = 1L,
            sourceSyncedAt = 1L,
            analyticsCachedAt = 1L,
            followerScanCompletedAt = 1L,
            followers = 10,
            following = 5,
            posts = 2,
            followersToday = 0,
            followersWeek = 0,
            cards = listOf(BriefCard("steady", BriefCardType.SUMMARY, "Steady", "All steady.", 50)),
            upcomingTweets = listOf(
                BriefUpcomingTweet(
                    id = "schedule",
                    provider = ScheduleProvider.BUFFER,
                    status = ScheduleStatus.SCHEDULED,
                    scheduledAt = 2L,
                    preview = secret,
                    threadCount = 1,
                    mediaCount = 0,
                ),
            ),
            topFollowerRanks = emptyMap(),
        )

        assertFalse(promptFor(snapshot, strings).contains(secret))
        assertFalse(localPromptFor(snapshot, strings).contains(secret))
        assertTrue(promptFor(snapshot, strings).contains("__brief_summary__"))
        assertTrue(promptFor(snapshot, strings).contains("shortDescription"))
        assertTrue(localPromptFor(snapshot, strings).contains("\"s\""))
        assertTrue(localPromptFor(snapshot, strings).contains("Use sentence case, never Title Case"))
    }

    @Test
    fun promptsAskForTheAppLanguageWhenItIsNotEnglish() {
        val german = TestBriefStrings(Locale.GERMAN)
        val snapshot = BriefSnapshot(
            username = "tester",
            generatedAt = 1L,
            sourceSyncedAt = 1L,
            analyticsCachedAt = 0L,
            followerScanCompletedAt = 0L,
            followers = 10L,
            following = 1L,
            posts = 1L,
            followersToday = 0L,
            followersWeek = 0L,
            cards = emptyList(),
            topFollowerRanks = emptyMap(),
        )

        assertTrue(promptFor(snapshot, german).contains("in German"))
        assertTrue(localPromptFor(snapshot, german).contains("in German"))
        assertFalse(promptFor(snapshot, strings).contains("in English"))
    }
}
