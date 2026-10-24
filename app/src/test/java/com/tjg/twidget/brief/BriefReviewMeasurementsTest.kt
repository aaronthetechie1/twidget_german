package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BriefReviewMeasurementsTest {
    private val now = 1_800_000_000_000L
    private val post = PostSummary("https://x.com/example/status/123", "Pixel 9 looks promising.", 1200, 12, 3, 4, 2, 21,
        now - 36 * 3_600_000L, "", "Example", "example", "", fullText = "Pixel 9 looks promising.")
    private val past = post.copy(url = "https://x.com/example/status/122", views = 6000, likes = 45)
    private fun input(language: String = "en-US") = BriefCoachInput("published", post.text, true, 0,
        BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList(), measurements = listOf("Your tweet received 1,200 views.")),
        listOf(past), now, language, reviewEvidence = BriefReviewEvidence(post, listOf(past, past, past), emptyList(), now))
    private fun feedback(explanation: String, reason: String = "Your opening is brief.", ids: List<String> = listOf("subject")) =
        BriefCoachFeedback(explanation, listOf(BriefCoachSuggestion("Name the feature.", reason, post.text, null, false,
            input().packet().citations.filter { it.id in ids })), null)

    @Test fun newResponsesUseTypedFactsInsteadOfSwappableLiteralCounts() {
        val packet = input().packet(cloud = true)
        assertTrue(BriefReviewNumbers.accepts(feedback("You received {{stat:performance:views}} and {{stat:performance:likes}}."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("You received 1200 likes and 12 views."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("You received {{stat:performance:followers}}."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("{{stat:missing:views}}"), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("{{stat:performance:views}"), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("Your wording is brief.", "You received 1200 likes."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("Your wording is brief.").let {
            it.copy(suggestions = it.suggestions.map { suggestion -> suggestion.copy(rewrite = "{{stat:performance:views}}") })
        }, packet))
    }

    @Test fun historicalFiguresRequireTheMatchingCitationAndRenderTheirSource() {
        val input = input()
        val claim = "Compare {{stat:p0:views}} with {{stat:performance:views}}."
        assertFalse(BriefReviewNumbers.accepts(feedback(claim), input.packet()))
        val supported = feedback(claim, ids = listOf("subject", "p0"))
        assertTrue(BriefReviewNumbers.accepts(supported, input.packet()))
        val displayed = BriefReviewCopy.clean(supported, input, TestBriefStrings()).possibleExplanation
        assertTrue(displayed.contains("6,000 views on your tweet from"))
        assertTrue(displayed.contains("1,200 views"))
        assertFalse(displayed.contains("{{"))
        assertFalse(displayed.contains("p0"))
        val compact = input.copy(history = listOf(past, past, past)).packet(compact = true)
        assertFalse(compact.data.getJSONObject("measurementReferences").has("{{stat:p2:views}}"))
    }

    @Test fun imageProductNamesAndVersionsAreContextNotEngagementCounts() {
        val packet = input().packet(attachedMediaIds = setOf("m0"), cloud = true)
        assertTrue(BriefReviewNumbers.accepts(feedback("The screenshot introduces Pixel 9 and version 1.3.1, so readers may understand your reaction."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("Pixel 9 received 6000 likes."), packet))
        assertTrue(BriefReviewNumbers.accepts(feedback("{{observation:e0}}"), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("{{observation:e9}}"), packet))
    }

    @Test fun legacyCachesCheckTheMetricAndSourceNotNumberMembership() {
        val packet = input().packet().copy(allowLegacyMeasurements = true)
        assertTrue(BriefReviewNumbers.accepts(feedback("You received 1,200 views and 12 likes."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("You received 12 views and 1,200 likes."), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("You received 6,000 views."), packet))
        assertTrue(BriefReviewNumbers.accepts(feedback("Your earlier tweet received 6,000 views.", ids = listOf("subject", "p0")), packet))
        assertFalse(BriefReviewNumbers.accepts(feedback("Your earlier tweet received 1,200 views.", ids = listOf("subject", "p0")), packet))
    }

    @Test fun typedFactsAndCodecRoundTripUseTheAppLocale() {
        for ((language, expected) in listOf("en-US" to "1,200 views", "fr-FR" to "1\u202f200 vues", "de-DE" to "1.200 Aufrufe")) {
            val input = input(language)
            val result = BriefCoachGeneration.Success(feedback("{{stat:performance:views}}; {{stat:performance:rate}}."), true)
            val restored = BriefCoachResultCodec.decode(BriefCoachResultCodec.encode(result), input)
            assertEquals(result, restored)
            val displayed = BriefReviewCopy.clean(restored.feedback, input, TestBriefStrings(Locale.forLanguageTag(language))).possibleExplanation
            assertTrue("$language: $displayed", displayed.contains(expected))
            assertFalse(displayed.contains("{{"))
            assertFalse(BriefReviewMeasurements.render("{{stat:history_comparison:views}}", input, TestBriefStrings(Locale.forLanguageTag(language))).contains("{{"))
        }
    }
}
