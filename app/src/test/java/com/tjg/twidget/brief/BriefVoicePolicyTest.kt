package com.tjg.twidget.brief

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BriefVoicePolicyTest {
    private val strings = TestBriefStrings()
    private val source = BriefSnapshot(username = "person", generatedAt = 1, sourceSyncedAt = 1,
        analyticsCachedAt = 1, followerScanCompletedAt = 0, followers = 100, following = 10, posts = 20,
        followersToday = 0, followersWeek = -2, topFollowerRanks = emptyMap(),
        cards = listOf(BriefCard("context", BriefCardType.POST_CONTEXT, "Your recent posts",
            "You received 1,200 engagements.", 70, metrics = listOf(BriefMetric("Engagements", "1,200")))))

    @Test fun `rejects first person account narration and spelled out stats`() {
        listOf("I share content regularly with a growing audience.", "We received 1,200 engagements.",
            "The account has accumulated a total of twelve hundred engagements.",
            "Your posts received twelve hundred engagements.", "This user has 100 followers.").forEach {
            assertFalse(it, BriefVoicePolicy.accepts(it, strings))
        }
        listOf("You received 1,200 engagements.", "Your follower count is down 2 this week.",
            "Try naming the feature in your opening.", "Your post says “I shipped this”.").forEach {
            assertTrue(it, BriefVoicePolicy.accepts(it, strings))
        }
    }

    @Test fun `spelling numbers out cannot bypass numeric fact validation`() {
        fun apply(body: String) = BriefAiCardResponse.apply(source,
            JSONArray().put(JSONObject().put("i", "context").put("t", "Your recent posts").put("b", body)).toString(),
            BriefProviderUsed.CLOUD, strings).snapshot!!.cards.single()
        assertFalse(apply("Your posts received twelve hundred engagements.").narrativeGenerated)
        assertFalse(apply("I share content regularly with a growing audience.").narrativeGenerated)
        assertTrue(apply("You received 1,200 engagements.").narrativeGenerated)
        assertFalse(apply("You received 9,000 engagements.").narrativeGenerated)
    }

    @Test fun `both structured prompts specify direct address digits and concrete copy`() {
        listOf(promptFor(source, strings), localPromptFor(source, strings)).forEach {
            assertTrue(it.contains(BriefVoicePolicy.INSTRUCTION))
            assertTrue(it.contains("Use digits"))
            assertTrue(it.contains("you and your"))
            assertTrue(it.contains(BriefVoicePolicy.PUNCTUATION_INSTRUCTION))
        }
    }

    @Test fun `generated Brief copy and saved runs remove em dashes in every language`() {
        listOf(java.util.Locale.US, java.util.Locale.FRENCH, java.util.Locale.GERMAN).forEach { locale ->
            val localized = TestBriefStrings(locale)
            val result = BriefAiCardResponse.apply(source,
                """[{"i":"context","t":"Your posts\u2014your context","b":"You received 1,200 engagements\u2014your recent posts."}]""",
                BriefProviderUsed.CLOUD, localized).snapshot!!
            assertTrue(result.cards.single().narrativeGenerated)
            assertFalse(result.cards.single().title.contains('\u2014'))
            assertEquals("You received 1,200 engagements, your recent posts.", result.cards.single().body)
        }
        val cached = source.copy(headline = "Your posts\u2014your context", subheading = "You received 1,200 engagements\u2014recently.",
            shortDescription = "Your posts\u2014recently.", cards = source.cards.map {
                it.copy(title = "Your posts\u2014recently", body = "You received 1,200 engagements\u2014recently.", narrativeGenerated = true)
            })
        val restored = BriefStore.decode(BriefStore.encode(cached))
        assertFalse(listOf(restored.headline, restored.subheading, restored.shortDescription,
            restored.cards.single().title, restored.cards.single().body).any { '\u2014' in it })
    }

    @Test fun `punctuation fallback preserves paragraphs numbers and existing punctuation`() {
        assertEquals("You received 1,200 views, up 5%.\n\nTry again. Keep the detail.",
            BriefVoicePolicy.withoutEmDashes("You received 1,200 views\u2014up 5%.\n\nTry again. \u2014 Keep the detail."))
        assertEquals("Your post, with context.", BriefVoicePolicy.withoutEmDashes("Your post \u2014 with context\u2014."))
        assertEquals("No change: 10-20, 10–20.", BriefVoicePolicy.withoutEmDashes("No change: 10-20, 10–20."))
    }

    @Test fun `milestone titles stay app owned when AI writes a description`() {
        val goal = BriefCard("goal", BriefCardType.MILESTONE, "Keep going", "Your target is 200 followers.", 80,
            metrics = listOf(BriefMetric("Current", "160"), BriefMetric("Target", "200"), BriefMetric("Progress", "80%")))
        val result = BriefAiCardResponse.apply(source.copy(cards = listOf(goal)),
            """[{"i":"goal","t":"Followers are slightly down over the last week","b":"You’re at 80% of your 200 follower goal."}]""",
            BriefProviderUsed.CLOUD, strings).snapshot!!.cards.single()
        assertEquals("Keep going", result.title)
        assertEquals("You’re at 80% of your 200 follower goal.", result.body)
        assertTrue(result.narrativeGenerated)
        assertEquals(goal.metrics, result.metrics)
    }
}
