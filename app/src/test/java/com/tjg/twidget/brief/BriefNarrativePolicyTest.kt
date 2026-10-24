package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefNarrativePolicyTest {
    private val strings = TestBriefStrings()
    private val source = BriefSnapshot(
        username = "person", generatedAt = 1, sourceSyncedAt = 1, analyticsCachedAt = 1,
        followerScanCompletedAt = 0, followers = 100, following = 20, posts = 30,
        followersToday = 0, followersWeek = -2, topFollowerRanks = emptyMap(),
        cards = listOf(
            BriefCard("context", BriefCardType.FOLLOWER_CONTEXT, "Source", "Recorded net change: -2.", 70,
                metrics = listOf(BriefMetric("Net follower change", "-2"))),
            BriefCard("streak", BriefCardType.STREAK, "Streak", "Your streak is 3 days.", 60),
        ),
    )

    private val summary = """{"i":"__brief_summary__","t":"A quieter stretch","b":"Your recorded follower count dipped. Your posting streak continues.","s":"A follower dip alongside an active streak."}"""
    private val context = """{"i":"context","t":"Your follower update","b":"A small dip in your recorded follower count."}"""
    private val streak = """{"i":"streak","t":"Keep your rhythm","b":"Your posting streak is still going."}"""

    @Test fun everyVisibleDescriptionAndTheSummaryMustBeGenerated() {
        val complete = BriefAiCardResponse.apply(source, "[$summary,$context,$streak]", BriefProviderUsed.CLOUD, strings).snapshot!!
        assertTrue(complete.hasGeneratedNarrative)
        assertEquals("-2", complete.cards.first().metrics.single().value)
        val partial = BriefAiCardResponse.apply(source, "[$summary,$context]", BriefProviderUsed.CLOUD, strings).snapshot!!
        assertFalse(partial.hasGeneratedNarrative)
        assertFalse(partial.cards.last().narrativeGenerated)
        val noSummary = BriefAiCardResponse.apply(source, "[$context,$streak]", BriefProviderUsed.LOCAL, strings).snapshot!!
        assertFalse(noSummary.hasGeneratedNarrative)
        assertFalse(source.hasGeneratedNarrative)
    }

    @Test fun missingOrInventedModelOutputCannotAcquireGeneratedStatus() {
        val invalid = """{"i":"context","t":"Big growth","b":"You gained 99 followers."}"""
        val result = BriefAiCardResponse.apply(source, "[$summary,$invalid,$streak]", BriefProviderUsed.CLOUD, strings).snapshot!!
        assertFalse(result.hasGeneratedNarrative)
        assertFalse(result.cards.first().narrativeGenerated)
    }

    @Test fun modelDescriptionsAndMetricValuesSurviveStorageTogether() {
        val complete = BriefAiCardResponse.apply(source, "[$summary,$context,$streak]", BriefProviderUsed.CLOUD, strings).snapshot!!
        val restored = BriefStore.decode(BriefStore.encode(complete))
        assertTrue(restored.hasGeneratedNarrative)
        assertEquals(complete.cards, restored.cards)
        val legacy = BriefStore.encode(complete).apply {
            remove("summaryGenerated")
            getJSONArray("cards").getJSONObject(0).remove("narrativeGenerated")
        }
        assertFalse(BriefStore.decode(legacy).hasGeneratedNarrative)
    }

    @Test fun generatedCompactCopyCanUseAccountFactsWithoutFallingBackToSourceProse() {
        val generated = summary.replace("A follower dip alongside an active streak.", "100 followers in view.")
        val result = BriefAiCardResponse.apply(source, "[$generated,$context,$streak]", BriefProviderUsed.CLOUD, strings).snapshot!!
        assertTrue(result.hasGeneratedNarrative)
        assertEquals("100 followers in view.", result.shortDescription)
    }

    @Test fun nanoBatchesRequestAllSuppliedCardsWithEvidenceAndMetrics() {
        val prompt = localPromptFor(source, strings, includeSummary = false)
        assertTrue(prompt.contains("all 2 supplied card descriptions"))
        assertTrue(prompt.contains("\"evidence\""))
        assertTrue(prompt.contains("\"metrics\""))
        assertFalse(prompt.contains("__brief_summary__"))
        assertTrue(promptFor(source, strings).contains("Account context"))
    }
}
