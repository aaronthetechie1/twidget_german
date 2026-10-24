package com.tjg.twidget.brief

import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostObservation
import com.tjg.twidget.analytics.PostObservationHistory
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.LocalUriMedia
import com.tjg.twidget.schedule.PublicUrlMedia
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BriefTweetReviewTest {
    private val now = 1_800_000_000_000L
    private fun post(id: Int = 0) = PostSummary("https://x.com/example/status/$id", "Shipped an age comparison tool.",
        1164, 12, 4, 5, 2, 999, now - 36 * 60 * 60_000L, "", "Example", "example", "",
        fullText = "Shipped an age comparison tool.")
    private fun analytics(at: Long = now, rows: List<PostSummary> = listOf(post())) = AnalyticsClient.analyticsFromPosts(
        "example", 100, rows.size, false, rows, cachedAt = at)
    private fun draft() = ScheduledPost(provider = ScheduleProvider.LOCAL_REMINDER, accountUsername = "example", scheduledAt = null,
        thread = listOf(ScheduleThreadItem(text = "Finally shipped this.", media = listOf(LocalUriMedia("content://fixture/a"))),
            ScheduleThreadItem(text = "It compares tweets at similar ages.")), updatedAt = now)
    private fun subject(draft: ScheduledPost = draft()): BriefReviewSubject {
        val strings = TestBriefStrings()
        val report = BriefPostCoach.draft(draft, analytics(), strings, now)
        return BriefReviewSubject(BriefReviewTarget("example", draftId = draft.id), BriefCoachAi.draft(draft, analytics(), report, strings, now), draft)
    }
    private fun suggestion(quote: String = "Finally shipped this.") = BriefCoachSuggestion("Name the feature.", "The feature is already visible in context.",
        quote, "I shipped a tool for comparing tweets at similar ages.", false, listOf(BriefCoachCitation("subject", quote)))

    @Test fun `review errors preserve the actual reason`() {
        assertEquals(com.tjg.twidget.R.string.review_error_cloud_key, briefReviewFailureMessage(BriefReviewFailure.MISSING_KEY))
        assertEquals(com.tjg.twidget.R.string.review_error_cloud, briefReviewFailureMessage(BriefReviewFailure.UNKNOWN))
    }

    @Test fun `quoted tweet is context not rewriteable wording and receives its own media slots`() {
        val original = post().copy(media = List(4) { PostMedia("photo", "https://example.com/own$it.jpg", "", 100, 100) },
            quotedPost = com.tjg.twidget.analytics.QuotedPost("https://x.com/source/status/9", "We shipped the comparison tool.", "Source", "source",
                media = listOf(PostMedia("photo", "https://example.com/source.jpg", "The comparison view", 100, 100))))
        val analytics = analytics(rows = listOf(original))
        val strings = TestBriefStrings()
        val report = BriefPostCoach.review(original, analytics, strings, now)!!
        val input = BriefCoachAi.review(original, analytics, report, strings, now)
        val packet = input.packet(attachedMediaIds = setOf("m0", "m1", "m2", "m3"), cloud = true)
        assertEquals(5, input.mediaCount)
        assertTrue(input.media.take(4).any { it.quoted })
        assertEquals("source", packet.data.getJSONObject("quotedTweet").getString("authorHandle"))
        assertTrue(packet.data.getJSONObject("quotedTweet").getBoolean("isSeparateSource"))
        assertTrue(packet.citations.any { it.id == "quoted_post" })
        assertTrue(packet.passages.values.none { it.contains("We shipped") })
        assertEquals("quoted_tweet", packet.data.getJSONArray("media").getJSONObject(2).getString("origin"))
        assertFalse(packet.data.getJSONObject("quotedTweet").has("views"))
        val seed = BriefReviewRewrite.seed("example", "My reaction.", 0, post = original)!!
        assertEquals("My reaction.\n\nhttps://x.com/source/status/9", seed.single().text)
        val linked = "My reaction. https://twitter.com/oldhandle/status/9"
        assertEquals(linked, BriefReviewRewrite.seed("example", linked, 0, post = original)!!.single().text)
        assertEquals(4, seed.single().media.size)
        assertTrue(seed.single().media.none { (it as? PublicUrlMedia)?.url == "https://example.com/source.jpg" })
        val clean = BriefReviewCopy.clean(BriefCoachFeedback("Read quoted_post.", listOf(suggestion()), null), input, strings)
        assertEquals("Read Quoted tweet.", clean.possibleExplanation)
    }

    @Test fun `reader copy replaces internal references without altering source quotes or citations`() {
        val input = subject().input
        val original = BriefCoachFeedback("Compare q0 with m0 and p0.", listOf(suggestion().copy(
            action = "Make q0 clearer using M0.", reason = "e0 and history_comparison describe performance, not causation.",
            rewrite = "A clearer version of q0.")), "Does m1 add context?")
        val clean = BriefReviewCopy.clean(original, input, TestBriefStrings())
        assertEquals("Compare the quoted wording with attachment 1 and a similar tweet.", clean.possibleExplanation)
        assertEquals("Make the quoted wording clearer using attachment 1.", clean.suggestions.single().action)
        assertTrue(clean.suggestions.single().reason.contains("describe performance, not causation"))
        assertEquals("A clearer version of the quoted wording.", clean.suggestions.single().rewrite)
        assertEquals("Does attachment 2 add context?", clean.question)
        assertEquals(original.suggestions.single().quote, clean.suggestions.single().quote)
        assertEquals(original.suggestions.single().citations, clean.suggestions.single().citations)
        val literal = original.copy(suggestions = listOf(suggestion().copy(rewrite = "The q0 compiler is faster.")))
        assertEquals("The q0 compiler is faster.", BriefReviewCopy.clean(literal,
            input.copy(text = "I shipped the q0 compiler."), TestBriefStrings()).suggestions.single().rewrite)
        assertEquals("Compare die zitierte Formulierung with Anhang 1 and ein ähnlicher Tweet.",
            BriefReviewCopy.clean(original, input, TestBriefStrings(java.util.Locale.GERMAN)).possibleExplanation)
    }

    @Test fun `observations record real fetch times without making up a daily history`() {
        val first = analytics(at = now - 12 * 60 * 60_000L)
        val firstRows = PostObservationHistory.record(null, first, now)
        val second = PostObservationHistory.record(firstRows, analytics(at = now - 6 * 60 * 60_000L), now)
        val third = PostObservationHistory.record(second, analytics(), now)
        val points = PostObservationHistory.read(third, post().url, now)
        assertEquals(listOf(first.cachedAt, now - 6 * 60 * 60_000L, now), points.map { it.at })
        assertEquals(listOf(23L, 23L, 23L), points.map { it.interactions })
        assertTrue(PostObservationHistory.read(third, "other post", now).isEmpty())
        assertTrue(PostObservationHistory.read(third, post().url, now + 8 * 24 * 60 * 60_000L).isEmpty())
        val future = PostObservationHistory.record(null, analytics(now + 1), now)
        assertEquals(0, future.length())
    }

    @Test fun `observations are bounded provider scoped valid counts and preserve unknown views`() {
        var history = JSONArray()
        repeat(50) { index ->
            val at = now - (49 - index) * 6 * 60 * 60_000L
            val p = post().copy(timestamp = at - 60_000L, viewsKnown = false)
            history = PostObservationHistory.record(history, analytics(at, listOf(p)), now)
        }
        val points = PostObservationHistory.read(history, post().url, now)
        assertTrue(points.size in 1..29)
        assertTrue(points.all { it.views == null && it.at <= now && it.at >= now - 7 * 24 * 60 * 60_000L })
        val invalid = listOf(post().copy(likes = -1), post(1).copy(authorUserName = "another"))
        assertEquals(0, PostObservationHistory.record(null, analytics(rows = invalid), now).length())
        assertEquals(24, PostObservationHistory.record(null, analytics(rows = (0..30).map(::post)), now).length())
    }

    @Test fun `review panels contain app counts and require enough real evidence`() {
        val evidence = BriefReviewEvidence.create(post(), analytics(), emptyList())
        assertEquals(23L, evidence.interactions) // Provider aggregate 999 is not the displayed public-interaction formula.
        assertFalse("weekly_trend" in evidence.panelIds)
        assertFalse("history_comparison" in evidence.panelIds)
        val rows = (0..3).map(::post)
        val observations = listOf(12L, 6L, 0L).map { hours -> PostObservation(now - hours * 60 * 60_000L, 1000, 10, 2, 1, 0) }
        val rich = BriefReviewEvidence.create(post(), analytics(rows = rows), observations)
        assertTrue("weekly_trend" in rich.panelIds)
        assertTrue("history_comparison" in rich.panelIds)
        val strings = TestBriefStrings()
        val report = BriefPostCoach.review(post(), analytics(rows = rows), strings, now)!!
        val input = BriefCoachAi.review(post(), analytics(rows = rows), report, strings, now)
            .copy(reviewEvidence = rich, reviewCitations = rich.citations(strings))
        val packet = input.packet()
        assertEquals(rich.panelIds, packet.availablePanels)
        assertTrue(packet.citations.any { it.id == "weekly_trend" })
        val response = JSONObject().put("possibleExplanation", "Response may depend on the opening.")
            .put("suggestions", JSONArray().put(JSONObject().put("quoteId", "q0").put("action", "Try a clearer benefit.")
                .put("reason", "This is a hypothesis.").put("rewrite", "").put("needsDetails", false)
                .put("evidenceIds", JSONArray(listOf("performance", "weekly_trend")))))
            .put("panelIds", JSONArray(listOf("weekly_trend", "imagined_clicks")))
        assertEquals(listOf("weekly_trend"), BriefCoachAi.parse(response.toString(), packet)!!.panelIds)
        assertEquals(3, packet.data.getJSONObject("visualEvidence").getJSONArray("recordedTotalsLastSevenDays").length())
    }

    @Test fun `rewrites open a separate thread preserving media and other entries`() {
        val original = draft()
        val subject = subject(original)
        assertEquals(0, BriefReviewRewrite.entryIndex(subject, suggestion()))
        val seed = BriefReviewRewrite.seed("example", suggestion().rewrite!!, 0, draft = original, expectedUpdatedAt = now)!!
        assertEquals(suggestion().rewrite, seed[0].text)
        assertEquals(original.thread[0].media, seed[0].media)
        assertEquals(original.thread[1].text, seed[1].text)
        assertEquals("Finally shipped this.", original.thread[0].text)
        assertNotEquals(original.thread[0].id, seed[0].id)
        assertNull(BriefReviewRewrite.seed("example", "New", 0, draft = original, expectedUpdatedAt = now - 1))
        assertNull(BriefReviewRewrite.seed("other", "New", 0, draft = original, expectedUpdatedAt = now))
        assertNull(BriefReviewRewrite.entryIndex(subject, suggestion("Not in the text")))
        assertNull(BriefReviewRewrite.entryIndex(subject, suggestion().copy(rewrite = null)))
    }

    @Test fun `published rewrite never attaches a video preview as an original video`() {
        val post = post().copy(media = listOf(PostMedia("photo", "https://example.com/a.jpg", "", 1, 1),
            PostMedia("video", "https://example.com/video-preview.jpg", "", 1, 1)))
        val seed = BriefReviewRewrite.seed("example", "New wording", 0, post = post)!!
        assertEquals(1, seed.single().media.size)
        assertNull(BriefReviewRewrite.seed("other", "New wording", 0, post = post))
        assertNull(BriefReviewRewrite.seed("example", "New wording", 0, post = post.copy(fullText = null)))
    }

    @Test fun `Buffer draft ownership follows the account id rather than channel id`() {
        val draft = draft().copy(provider = ScheduleProvider.BUFFER, accountId = "example", accountUsername = "channel-id")
        assertEquals(listOf(draft), BriefPostCoach.readyDrafts(listOf(draft), "example"))
        assertTrue(BriefPostCoach.readyDrafts(listOf(draft), "channel-id").isEmpty())
        assertNotNull(BriefReviewRewrite.seed("example", "New text", 0, draft = draft, expectedUpdatedAt = now))
        assertTrue(subject(draft).input.history.any { it.authorUserName == "example" })
    }

    @Test fun `review cache separates providers languages and draft revisions and expires`() {
        val subject = subject()
        val local = BriefReviewResults.key(subject, BriefProviderMode.LOCAL, BriefNanoModelMode.AUTO_STABLE)
        val auto = BriefReviewResults.key(subject, BriefProviderMode.AUTO, BriefNanoModelMode.AUTO_STABLE)
        assertNotEquals(local, auto)
        assertNotEquals(local, BriefReviewResults.key(subject.copy(input = subject.input.copy(language = "de")),
            BriefProviderMode.LOCAL, BriefNanoModelMode.AUTO_STABLE))
        assertNotEquals(local, BriefReviewResults.key(subject.copy(draft = subject.draft!!.copy(updatedAt = now + 1)),
            BriefProviderMode.LOCAL, BriefNanoModelMode.AUTO_STABLE))
        val result = BriefCoachGeneration.Success(BriefCoachFeedback("A hypothesis.", listOf(suggestion()), null), false)
        BriefReviewResults.put(local, result, now)
        assertSame(result, BriefReviewResults.get(local, now + 1))
        assertNull(BriefReviewResults.get(auto, now + 1))
        assertNull(BriefReviewResults.get(local, now + 60 * 60_000L))
    }

    @Test fun `evidence comparison ignores polling time but notices useful changes`() {
        val post = post()
        val evidence = BriefReviewEvidence(post, emptyList(), listOf(PostObservation(now, post.views,
            post.likes, post.replies, post.reposts, post.quotes)), now)
        val original = BriefReviewSubject(BriefReviewTarget("example", postUrl = post.url),
            subject().input.copy(text = post.fullText!!, reviewEvidence = evidence), post = post)
        val polled = original.copy(input = original.input.copy(observedAt = now + 60_000,
            reviewEvidence = evidence.copy(observedAt = now + 60_000,
                observations = evidence.observations + evidence.observations.last().copy(at = now + 60_000))))
        assertTrue(BriefReviewEvidenceFingerprint.same(original, polled))
        assertFalse(BriefReviewEvidenceFingerprint.same(original, polled.copy(post = post.copy(views = post.views + 1))))
        assertFalse(BriefReviewEvidenceFingerprint.same(original, polled.copy(input = polled.input.copy(text = "Changed text"))))
        assertFalse(BriefReviewEvidenceFingerprint.same(original, polled.copy(input = polled.input.copy(history = listOf(post(2))))))
        val growth = polled.input.reviewEvidence!!.copy(observations = evidence.observations +
            evidence.observations.last().copy(at = now + 60_000, likes = post.likes + 1))
        assertFalse(BriefReviewEvidenceFingerprint.same(original, polled.copy(input = polled.input.copy(reviewEvidence = growth))))
    }
}
