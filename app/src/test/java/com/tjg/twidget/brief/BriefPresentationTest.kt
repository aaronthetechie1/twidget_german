package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefPresentationTest {
    private val strings = TestBriefStrings()
    private val complete = BriefSnapshot("person", 1, 2, 3, 0, 100, 20, 30, 1, 2,
        listOf(BriefCard("growth", BriefCardType.GROWTH, "Your follower update", "You gained 1 follower.", 80,
            narrativeGenerated = true)), headline = "Your progress", subheading = "You gained 1 follower.",
        shortDescription = "Your audience grew.", topFollowerRanks = emptyMap(), providerUsed = BriefProviderUsed.LOCAL,
        language = strings.languageTag, summaryGenerated = true, aiGeneratedAt = 1, contentSettingsFingerprint = "content")

    @Test fun expiredSuccessfulCopyRemainsDisplayableWithoutBecomingFresh() {
        val remembered = BriefPresentation.from(complete, strings, "content")!!
        assertTrue(remembered.matches(strings, "content"))
        assertFalse(BriefAiCachePolicy.isFresh(complete, 1_800_000_000_000L))
        assertEquals("You gained 1 follower.", remembered.summary().body)
        assertNull(BriefPresentation.from(complete.copy(summaryGenerated = false), strings, "content"))
        assertNull(BriefPresentation.from(complete.copy(providerUsed = BriefProviderUsed.TEMPLATE), strings, "content"))
    }

    @Test fun displayMemoryHonorsLanguageAndContentSwitchesAndRejectsLegacyRoboticCopy() {
        val remembered = BriefPresentation.from(complete, strings, "content")!!
        assertFalse(remembered.matches(TestBriefStrings(java.util.Locale.GERMAN), "content"))
        assertFalse(remembered.matches(strings, "disabled follower content"))
        assertFalse(remembered.copy(body = "I share content regularly with a growing audience.").matches(strings, "content"))
        assertFalse(remembered.copy(shortDescription = "You gained one follower.").matches(strings, "content"))
    }

    @Test fun olderSuccessfulRunsCanRecoverTheirOriginalContentSwitches() {
        val old = complete.copy(contentSettingsFingerprint = "", contextFingerprint = "goal|streak|top_tweet:true,followers:true|local|auto_stable|en")
        val remembered = BriefPresentation.from(old, strings, "new settings")!!
        assertEquals("top_tweet:true,followers:true", remembered.contentFingerprint)
        assertFalse(remembered.matches(strings, "new settings"))
    }
}
