package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefEditorialSummaryTest {
    @Test
    fun summaryUsesRankedLeadAndCombinesTopFactsWithoutConsumingCards() {
        val cards = listOf(
            BriefCard("post", BriefCardType.POST, "Getting attention", "Your post got 100K impressions.", 98),
            BriefCard("growth", BriefCardType.GROWTH, "Growing", "Followers increased by 20.", 90),
            BriefCard("follower", BriefCardType.TOP_FOLLOWER, "New top follower", "@JohnCena is now your second most popular follower.", 80),
            BriefCard("streak", BriefCardType.STREAK, "On a roll", "Your streak is safe.", 70),
        )

        val summary = BriefEditorialSummary.from(cards)

        assertEquals("Getting attention", summary.title)
        assertEquals(
            "Your post got 100K impressions. Followers increased by 20. " +
                "@JohnCena is now your second most popular follower.",
            summary.body,
        )
        assertEquals(4, cards.size)
    }

    @Test
    fun summaryHasAStableEmptyFallback() {
        val summary = BriefEditorialSummary.from(emptyList())

        assertEquals("Your Twidget Brief", summary.title)
        assertEquals(
            "Twidget is watching for your next meaningful account update.",
            summary.body,
        )
    }
}
