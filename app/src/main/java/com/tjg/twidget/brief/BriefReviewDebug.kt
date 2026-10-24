package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.ScheduleStore
import org.json.JSONArray
import org.json.JSONObject

/** Debug reports never include credentials. Only an explicit evidence inspection reads source text. */
internal object BriefReviewDebug {
    data class Choice(val label: String, val target: BriefReviewTarget)

    fun choices(context: Context, username: String): List<Choice> {
        val analytics = AnalyticsClient.cached(context, username)
        val posts = (BriefPostCoach.reviewTargets(analytics, username, System.currentTimeMillis()) + listOfNotNull(analytics?.banger) +
            BriefPublishedReviewStore.posts(context, username, 100)).distinctBy { BriefPublishedReviewStore.postId(it.url) }
        return posts.map { Choice("${if (it.isReply) "Reply" else "Tweet"} · ${it.text.replace('\n', ' ').take(80)}",
            BriefReviewTarget(username, postUrl = it.url, fromDashboard = true)) } +
            BriefPostCoach.readyDrafts(ScheduleStore(context).listForAccount(username), username).map {
                Choice("Draft · ${it.thread.firstOrNull()?.text.orEmpty().replace('\n', ' ').take(80)}", BriefReviewTarget(username, draftId = it.id))
            }
    }

    fun subject(context: Context, target: BriefReviewTarget): BriefReviewSubject? =
        BriefReviewSubjects.load(context, target) ?: BriefPublishedReviewStore.read(context, target)?.let {
            BriefReviewSubjects.fromSaved(it, BriefStrings.from(context))
        }

    fun evidence(context: Context, target: BriefReviewTarget): String {
        val subject = subject(context, target) ?: return "Source unavailable. Check account, content eligibility and cached analytics."
        val input = subject.input
        return buildString {
            appendLine("Kind: ${input.kind} · language: ${input.language}")
            appendLine("Complete source text: ${input.complete} · rewrite allowed: ${input.packet().canRewrite}")
            appendLine("History examples: ${input.history.size} · media: ${input.media.size}")
            appendLine("Quoted tweet: ${input.quotedPost != null} · observations: ${input.reviewEvidence?.observations?.size ?: 0}")
            appendLine("Available panels: ${input.reviewEvidence?.panelIds.orEmpty().joinToString()}")
            appendLine("No media has been sent to a model by this inspection.\n")
            append(input.packet().data.toString(2))
        }
    }

    fun cache(context: Context, target: BriefReviewTarget, now: Long = System.currentTimeMillis()): String {
        if (target.draftId != null) {
            val current = subject(context, target) ?: return "Draft unavailable."
            val key = BriefReviewResults.key(current, BriefProviderMode.CLOUD, BriefNanoModelMode.AUTO_STABLE)
            val saved = BriefPublishedReviewStore.readDraft(context, current, key, now)
            return "Draft reviews use a private, durable cache keyed by revision, evidence and language. " +
                "Advice is retained for up to 7 days, at most 32 drafts per account. " +
                (saved?.let { "Matching review saved at: ${java.util.Date(it.savedAt)}" } ?: "No matching saved review.")
        }
        val saved = BriefPublishedReviewStore.read(context, target) ?: return "No saved review for this tweet."
        val language = BriefStrings.from(context).languageTag
        val lifetime = BriefReviewFreshness.lifetime(saved.subject.post?.timestamp ?: 0L, saved.checkedAt)
        return buildString {
            appendLine("Saved at: ${java.util.Date(saved.savedAt)}")
            appendLine("Evidence last checked: ${java.util.Date(saved.checkedAt)}")
            appendLine("Age: ${((now - saved.savedAt) / 60_000L).coerceAtLeast(0)} min · lifetime: ${lifetime / 3_600_000} h")
            appendLine("Saved language: ${saved.subject.input.language} · app: $language")
            appendLine("Language matches: ${BriefReviewFreshness.matchesLanguage(saved, language)}")
            appendLine("Fresh: ${BriefReviewFreshness.isFresh(saved, now)} · reusable: ${BriefReviewFreshness.canReuse(saved, language, now)}")
            appendLine("Cloud: ${saved.result.cloud} · inspected media: ${saved.result.inspectedMediaIds.size}")
            appendLine("Suggestions: ${saved.result.feedback.suggestions.size} · panels: ${saved.result.feedback.panelIds.joinToString()}")
            append("A failed refresh keeps the last successful review.")
        }
    }

    fun checks(): String {
        val checks = linkedMapOf(
            "Local-only blocks cloud, even with a key" to (BriefReviewPolicy.blocked(BriefProviderMode.LOCAL, true) == BriefReviewFailure.LOCAL_ONLY),
            "Auto requires a key" to (BriefReviewPolicy.blocked(BriefProviderMode.AUTO, false) == BriefReviewFailure.MISSING_KEY),
            "Cloud requires a key" to (BriefReviewPolicy.blocked(BriefProviderMode.CLOUD, false) == BriefReviewFailure.MISSING_KEY),
            "Configured Auto permits a review" to (BriefReviewPolicy.blocked(BriefProviderMode.AUTO, true) == null),
            "Configured Cloud permits a review" to (BriefReviewPolicy.blocked(BriefProviderMode.CLOUD, true) == null),
            "First-day reviews expire after 4 h" to (BriefReviewFreshness.lifetime(1, 3_600_001) == 4 * 3_600_000L),
            "Week-old activity uses a 12 h refresh window" to (BriefReviewFreshness.lifetime(1, 48 * 3_600_000L) == 12 * 3_600_000L),
            "Older tweets use a 48 h refresh window" to (BriefReviewFreshness.lifetime(1, 8 * 24 * 3_600_000L) == 48 * 3_600_000L),
        )
        return checks.entries.joinToString("\n") { "${if (it.value) "PASS" else "FAIL"} · ${it.key}" } +
            "\n\nNo model calls, settings changes or saved-review changes."
    }
}

/** Bounded metadata only: never prompts, tweet/draft text, model output, images or API keys. */
internal object BriefReviewDebugLog {
    private const val PREFS = "twidget_review_debug"
    @Synchronized fun record(context: Context, event: String, data: JSONObject) {
        if (!TwidgetStore.debugMenuUnlocked(context)) return
        // A damaged optional debug log must never fail a production review.
        runCatching {
            val list = entries(context)
            val next = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("event", event).put("data", data))
            for (index in 0 until minOf(list.length(), 39)) list.optJSONObject(index)?.let { next.put(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("events", next.toString()).apply()
        }
    }
    fun entries(context: Context): JSONArray = runCatching {
        val stored = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "[]"))
        JSONArray().apply { for (index in 0 until stored.length()) stored.optJSONObject(index)?.takeIf {
            it.optString("event").isNotBlank() && it.optLong("at") > 0 && it.optJSONObject("data") != null
        }?.let { put(it) } }
    }.getOrElse { JSONArray() }
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    fun state(context: Context, target: BriefReviewTarget, state: BriefReviewState) = record(context, state.phase.name,
        JSONObject().put("account", target.username).put("kind", if (target.draftId != null) "draft" else "tweet")
            .put("postId", target.postUrl?.let(BriefPublishedReviewStore::postId) ?: JSONObject.NULL)
            .put("language", state.subject?.input?.language ?: JSONObject.NULL)
            .put("failure", state.failure?.name ?: JSONObject.NULL).put("savedAt", state.savedAt ?: JSONObject.NULL)
            .put("mediaCount", state.subject?.input?.media?.size ?: 0).put("historyCount", state.subject?.input?.history?.size ?: 0))
}
