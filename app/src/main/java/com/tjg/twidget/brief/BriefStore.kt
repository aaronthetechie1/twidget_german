package com.tjg.twidget.brief

import android.content.Context
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object BriefStore {
    private const val PREFS = "twidget_briefs"

    fun read(context: Context, username: String): BriefSnapshot? {
        val raw = prefs(context).getString(key(username), null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun write(context: Context, snapshot: BriefSnapshot) {
        prefs(context).edit().putString(key(snapshot.username), encode(snapshot).toString()).apply()
    }

    fun resetAi(context: Context, username: String) {
        read(context, username)?.let { snapshot ->
            write(
                context,
                snapshot.copy(
                    providerUsed = BriefProviderUsed.TEMPLATE,
                    providerMessage = "Built on device from your Twidget data",
                ),
            )
        }
    }

    private fun encode(snapshot: BriefSnapshot) = JSONObject().apply {
        put("username", snapshot.username)
        put("generatedAt", snapshot.generatedAt)
        put("sourceSyncedAt", snapshot.sourceSyncedAt)
        put("analyticsCachedAt", snapshot.analyticsCachedAt)
        put("followerScanCompletedAt", snapshot.followerScanCompletedAt)
        put("followers", snapshot.followers)
        put("following", snapshot.following)
        put("posts", snapshot.posts)
        put("followersToday", snapshot.followersToday)
        put("followersWeek", snapshot.followersWeek)
        put("engineVersion", snapshot.engineVersion)
        put("contextFingerprint", snapshot.contextFingerprint)
        put("providerUsed", snapshot.providerUsed.name)
        put("providerMessage", snapshot.providerMessage)
        put("cards", JSONArray().apply {
            snapshot.cards.forEach { card ->
                put(JSONObject().apply {
                    put("id", card.id)
                    put("type", card.type.name)
                    put("title", card.title)
                    put("body", card.body)
                    put("score", card.score)
                })
            }
        })
        put("topFollowerRanks", JSONObject().apply {
            snapshot.topFollowerRanks.forEach { (id, rank) -> put(id, rank) }
        })
    }

    private fun decode(root: JSONObject): BriefSnapshot {
        val cardsJson = root.optJSONArray("cards") ?: JSONArray()
        val ranksJson = root.optJSONObject("topFollowerRanks") ?: JSONObject()
        return BriefSnapshot(
            username = root.optString("username"),
            generatedAt = root.optLong("generatedAt"),
            sourceSyncedAt = root.optLong("sourceSyncedAt"),
            analyticsCachedAt = root.optLong("analyticsCachedAt"),
            followerScanCompletedAt = root.optLong("followerScanCompletedAt"),
            followers = root.optLong("followers"),
            following = root.optLong("following"),
            posts = root.optLong("posts"),
            followersToday = root.optLong("followersToday"),
            followersWeek = root.optLong("followersWeek"),
            cards = buildList {
                for (index in 0 until cardsJson.length()) {
                    val card = cardsJson.getJSONObject(index)
                    add(BriefCard(
                        id = card.optString("id"),
                        type = runCatching { BriefCardType.valueOf(card.optString("type")) }
                            .getOrDefault(BriefCardType.SUMMARY),
                        title = card.optString("title"),
                        body = card.optString("body"),
                        score = card.optInt("score"),
                    ))
                }
            },
            topFollowerRanks = buildMap {
                ranksJson.keys().forEach { id -> put(id, ranksJson.optInt(id)) }
            },
            engineVersion = root.optInt("engineVersion"),
            contextFingerprint = root.optString("contextFingerprint"),
            providerUsed = runCatching { BriefProviderUsed.valueOf(root.optString("providerUsed")) }
                .getOrDefault(BriefProviderUsed.TEMPLATE),
            providerMessage = root.optString(
                "providerMessage",
                "Built on device from your Twidget data",
            ),
        )
    }

    private fun key(username: String): String =
        username.trim().trimStart('@').lowercase(Locale.US)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
