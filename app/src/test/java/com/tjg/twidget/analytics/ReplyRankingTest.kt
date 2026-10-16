package com.tjg.twidget.analytics

import com.tjg.twidget.banger.BangerClient
import com.tjg.twidget.banger.BangerScore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyRankingTest {
    private val now = System.currentTimeMillis()
    private val weekAgo = now - 7 * 86400000L

    private fun tweet(id: String, likes: Int, reply: Boolean = false): JSONObject = JSONObject()
        .put("id", id).put("createdAt", java.time.Instant.ofEpochMilli(now - 2 * 86400000L).toString())
        .put("author", JSONObject().put("userName", "example"))
        .put("likeCount", likes).put("viewCount", 10000)
        .put("conversationId", if (reply) "parent" else id)
        .put("isReply", reply)

    @Test fun `breakout reply wins weekly best while quiet replies never enter worst or baseline`() {
        val originals = listOf(tweet("1", 10), tweet("2", 20))
            .map { AnalyticsClient.parseTwitterApisPost(it, "example", weekAgo, now)!! }
        val replies = listOf(tweet("3", 1000, true), tweet("4", 0, true))
            .map { AnalyticsClient.parseTwitterApisPost(it, "example", weekAgo, now, includeReplies = true)!! }
        val analytics = AnalyticsClient.analyticsFromPosts("example", 100, 4, false, originals, originals + replies, now)
        assertEquals(replies.first(), analytics.best)
        assertEquals(originals.first(), analytics.worst)
        assertEquals(2, analytics.postsAnalyzed)
        assertEquals(30L, analytics.totalEngagements)
        assertEquals(originals, analytics.recentPosts)
        assertTrue(BangerScore.calculate(replies.first()) > BangerScore.calculate(originals.last()))
        val onlyReply = AnalyticsClient.analyticsFromPosts("example", 100, 2, false, emptyList(), replies, now)
        assertEquals(replies.first(), onlyReply.best)
        assertNull(onlyReply.worst)
        val briefCard = com.tjg.twidget.brief.BriefEngine.standoutPostCard(
            onlyReply, 100, com.tjg.twidget.brief.TestBriefStrings(),
        )
        assertNotNull("Your Brief must surface a breakout reply even with no original posts", briefCard)
        assertEquals(com.tjg.twidget.brief.BriefCardType.POST, briefCard!!.type)
        assertEquals("post-${replies.first().timestamp}", briefCard.id)
    }

    @Test fun `twitterapis includes reply markers and conversation replies only when requested`() {
        for (status in listOf(tweet("1", 5, true), tweet("2", 5).put("inReplyToId", "parent"), tweet("3", 5).put("conversationId", "parent"))) {
            assertNull(AnalyticsClient.parseTwitterApisPost(status, "example", weekAgo, now))
            assertNotNull(AnalyticsClient.parseTwitterApisPost(status, "example", weekAgo, now, true))
        }
        assertNull(AnalyticsClient.parseTwitterApisPost(tweet("4", 5, true).put("isRetweet", true), "example", weekAgo, now, true))
        assertNull(AnalyticsClient.parseTwitterApisPost(tweet("5", 5, true).put("author", JSONObject().put("userName", "other")), "example", weekAgo, now, true))
    }

    @Test fun `fx replies qualify for both best rankings but not original-only analytics`() {
        val status = JSONObject().put("type", "status").put("id", "123")
            .put("author", JSONObject().put("screen_name", "example"))
            .put("url", "https://x.com/example/status/123").put("created_timestamp", (now - 86400000L) / 1000)
            .put("conversation_id", "parent").put("replying_to", "another")
        assertFalse(AnalyticsClient.isOwnWeeklyPost(status, "example", weekAgo, now))
        assertTrue(AnalyticsClient.isOwnWeeklyPost(status, "example", weekAgo, now, true))
        assertTrue(BangerClient.isOwnPost(status, "example"))
        assertFalse(BangerClient.isOwnPost(JSONObject(status.toString()).put("reposted_by", "example"), "example"))
        assertFalse(BangerClient.isOwnPost(JSONObject(status.toString()).put("author", JSONObject().put("screen_name", "other")), "example"))
    }
}
