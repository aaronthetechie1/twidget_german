package com.tjg.twidget.analytics

import com.tjg.twidget.core.NetworkResponseParsers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostCoachingDataTest {
    @Test fun `legacy bridge previews stay incomplete and new reply lists remain separate`() {
        val legacy = JSONObject().put("url", "https://x.com/example/status/1").put("text", "An excerpt")
        val reply = JSONObject(legacy.toString()).put("isReply", true).put("fullText", "The complete reply")
        val root = JSONObject().put("recentPosts", JSONArray().put(legacy))
            .put("recentReplies", JSONArray().put(reply).put(legacy))
        val result = NetworkResponseParsers.parseBridgeAnalytics(root)
        assertNull(result.recentPosts.single().fullText)
        assertFalse(result.recentPosts.single().isReply)
        assertEquals("The complete reply", result.recentReplies.single().fullText)
        assertTrue(result.recentReplies.single().isReply)
    }

    @Test fun `direct providers retain full text and correctly classify conversation replies`() {
        val text = "Useful details about Android widgets. ".repeat(12)
        val raw = JSONObject().put("id", "2").put("conversation_id", "1").put("text", text)
            .put("created_timestamp", 1_799_900_000).put("author", JSONObject().put("screen_name", "example"))
        val fx = AnalyticsClient.parseFxPost(raw)
        assertEquals(text, fx.fullText)
        assertTrue(fx.text.length <= 180)
        assertTrue(fx.isReply)
        val twitter = JSONObject().put("id", "2").put("conversationId", "1").put("text", text)
            .put("timestamp", 1_799_900_000_000L).put("author", JSONObject().put("userName", "example"))
        val post = AnalyticsClient.parseTwitterApisPost(twitter, "example", 1_799_000_000_000L, 1_800_000_000_000L, true)!!
        assertTrue(post.isReply)
        assertEquals(text, post.fullText)
        assertNull(AnalyticsClient.parseFxPost(raw.put("truncated", true)).fullText)
    }
    @Test fun `quoted sources retain bounded text author and media without mixing their counts into the post`() {
        val source = JSONObject().put("url", "https://x.com/source/status/9").put("text", "We shipped the comparison tool.")
            .put("created_timestamp", 1_799_900_000L)
            .put("author", JSONObject().put("screen_name", "source").put("name", "Source"))
            .put("views", 999999).put("likes", 9999)
            .put("quote", JSONObject().put("text", "Do not recursively archive me"))
            .put("media", JSONObject().put("all", JSONArray().put(JSONObject().put("type", "photo").put("url", "https://example.com/source.jpg"))))
        val raw = JSONObject().put("url", "https://x.com/example/status/1").put("text", "That is useful.")
            .put("author", JSONObject().put("screen_name", "example")).put("views", 100).put("likes", 2).put("quote", source)
        val fx = AnalyticsClient.parseFxPost(raw)
        assertEquals(100L, fx.views)
        assertEquals(2L, fx.likes)
        assertEquals("That is useful.", fx.fullText)
        assertEquals("source", fx.quotedPost!!.authorUserName)
        assertEquals(1_799_900_000_000L, fx.quotedPost!!.timestamp)
        assertEquals("We shipped the comparison tool.", fx.quotedPost!!.text)
        assertEquals("https://example.com/source.jpg", fx.quotedPost!!.media.single().url)
        assertEquals(fx, NetworkResponseParsers.parseBridgePost(AnalyticsClient.serializePost(fx)))
        assertFalse(AnalyticsClient.serializePost(fx).getJSONObject("quotedPost").has("views"))
        assertFalse(AnalyticsClient.serializePost(fx).getJSONObject("quotedPost").has("quotedPost"))
        val twitter = JSONObject().put("id", "1").put("text", "That is useful.").put("timestamp", 1_799_900_000_000L)
            .put("author", JSONObject().put("userName", "example"))
            .put("quoted_tweet", JSONObject().put("id", "9").put("full_text", "q".repeat(10001))
                .put("author", JSONObject().put("username", "source")))
        val quoted = AnalyticsClient.parseTwitterApisPost(twitter, "example", 1_799_000_000_000L, 1_800_000_000_000L)!!.quotedPost!!
        assertEquals("https://x.com/i/web/status/9", quoted.url)
        assertEquals(10000, quoted.text.length)
        assertFalse(quoted.textComplete)
    }

}
