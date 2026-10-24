package com.tjg.twidget.brief

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefRunCacheInstrumentedTest {
    @Test fun recentSuccessfulRunsSurviveTemplateWritesAndRemainAccountScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "brief_cache_fixture"
        val prefs = context.getSharedPreferences("twidget_briefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(account, null)
        val now = System.currentTimeMillis()
        fun snapshot(id: String) = BriefSnapshot(username = account, generatedAt = now, sourceSyncedAt = 1L,
            analyticsCachedAt = 2L, followerScanCompletedAt = 3L, followers = 100, following = 10, posts = 20,
            followersToday = 1, followersWeek = 3, topFollowerRanks = emptyMap(),
            cards = listOf(BriefCard(id, BriefCardType.GROWTH, "One new follower", "You gained 1 follower.", 90,
                narrativeGenerated = true)), headline = "Progress", subheading = "One new follower",
            shortDescription = "One new follower", summaryGenerated = true, providerUsed = BriefProviderUsed.LOCAL,
            language = BriefStrings.from(context).languageTag, aiGeneratedAt = now, contextFingerprint = id)
        try {
            val first = snapshot("growth")
            val second = snapshot("context")
            BriefStore.write(context, first)
            BriefStore.write(context, second)
            val template = first.copy(providerUsed = BriefProviderUsed.TEMPLATE, summaryGenerated = false,
                cards = first.cards.map { it.copy(narrativeGenerated = false) })
            BriefStore.write(context, template)
            // Check persistence independently of the user's current provider selection.
            val root = org.json.JSONObject(prefs.getString(account, null)!!)
            val runs = root.getJSONArray("recentRuns")
            assertEquals(2, runs.length())
            val restored = BriefRunCache.find((0 until runs.length()).map { BriefStore.decode(runs.getJSONObject(it)) },
                template, BriefProviderMode.LOCAL, BriefStrings.from(context))!!
            assertTrue(restored.hasGeneratedNarrative)
            assertEquals("growth", restored.cards.single().id)
            assertNull(BriefRunCache.find(listOf(restored), template.copy(username = "other"),
                BriefProviderMode.LOCAL, BriefStrings.from(context)))
        } finally {
            prefs.edit().apply { if (saved == null) remove(account) else putString(account, saved) }.commit()
        }
    }
}
