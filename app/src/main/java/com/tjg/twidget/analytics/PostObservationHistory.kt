package com.tjg.twidget.analytics

import org.json.JSONArray
import org.json.JSONObject

internal data class PostObservation(val at: Long, val views: Long?, val likes: Long,
    val replies: Long, val reposts: Long, val quotes: Long) {
    val interactions: Long get() = publicInteractions(likes, replies, reposts, quotes)
}

internal fun publicInteractions(vararg counts: Long): Long = counts.fold(0L) { total, value ->
    if (value > Long.MAX_VALUE - total) Long.MAX_VALUE else total + value
}

/** Recorded totals from actual analytics fetches. Never backfill unobserved days. */
internal object PostObservationHistory {
    const val FIELD = "postObservations"
    private const val WEEK = 7 * 24 * 60 * 60_000L
    private const val BUCKET = 6 * 60 * 60_000L
    private const val MAX_POSTS = 24
    private const val MAX_POINTS = 32

    fun record(previous: JSONArray?, analytics: PostAnalytics, now: Long = System.currentTimeMillis()): JSONArray {
        val entries = linkedMapOf<String, List<PostObservation>>()
        val observedAt = analytics.cachedAt
        val validObservation = observedAt in 1..now
        val posts = (analytics.recentPosts + analytics.recentReplies)
            .filter { PostAgeComparison.valid(it) && it.authorUserName.equals(analytics.userName, true) &&
                it.timestamp in 1..observedAt }
            .distinctBy(PostSummary::url).sortedByDescending(PostSummary::timestamp).take(MAX_POSTS)
        if (validObservation) posts.forEach { post ->
            val latest = PostObservation(observedAt, post.views.takeIf { post.viewsKnown },
                post.likes, post.replies, post.reposts, post.quotes)
            entries[post.url] = bounded(read(previous, post.url, now) + latest, now)
        }
        for (index in 0 until minOf(previous?.length() ?: 0, MAX_POSTS)) {
            val url = previous?.optJSONObject(index)?.optString("url").orEmpty()
            if (url.isNotBlank() && url !in entries) entries[url] = read(previous, url, now)
        }
        return JSONArray(entries.filterValues { it.isNotEmpty() }.entries.take(MAX_POSTS).map { (url, points) ->
            JSONObject().put("url", url).put("points", JSONArray(points.map { point ->
                JSONObject().put("at", point.at).put("views", point.views ?: JSONObject.NULL)
                    .put("likes", point.likes).put("replies", point.replies)
                    .put("reposts", point.reposts).put("quotes", point.quotes)
            }))
        })
    }

    fun read(history: JSONArray?, url: String, now: Long = System.currentTimeMillis()): List<PostObservation> {
        val row = (0 until minOf(history?.length() ?: 0, MAX_POSTS)).mapNotNull { history?.optJSONObject(it) }
            .firstOrNull { it.optString("url") == url } ?: return emptyList()
        val points = row.optJSONArray("points") ?: return emptyList()
        return bounded((0 until minOf(points.length(), MAX_POINTS)).mapNotNull { index ->
            val item = points.optJSONObject(index) ?: return@mapNotNull null
            val counts = listOf("likes", "replies", "reposts", "quotes").map { item.optLong(it, -1L) }
            val views = if (item.isNull("views")) null else item.optLong("views", -1L)
            if (counts.any { it < 0 } || views?.let { it < 0 } == true) return@mapNotNull null
            PostObservation(item.optLong("at"), views, counts[0], counts[1], counts[2], counts[3])
        }, now)
    }

    private fun bounded(points: List<PostObservation>, now: Long): List<PostObservation> = points
        .filter { it.at > 0 && it.at in (now - WEEK)..now }
        .sortedByDescending(PostObservation::at).distinctBy { it.at / BUCKET }
        .take(MAX_POINTS).sortedBy(PostObservation::at)
}
