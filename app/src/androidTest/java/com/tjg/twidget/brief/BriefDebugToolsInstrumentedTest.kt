package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.settings.BriefDebugActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BriefDebugToolsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun debugHomeUsesCompactGroupedNavigationAndDetailsLiveOnSubpages() = withDebug {
        ActivityScenario.launch(BriefDebugActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<ToolbarLayout>(R.id.preference_toolbar_layout)
                assertFalse(toolbar.isExpandable)
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                for (key in listOf("components", "onboarding", "review_preview", "launch", "ai", "ranking", "review_ui", "review_engine", "review_ai", "review_log"))
                    assertNotNull(key, fragment.findPreference<Preference>(key))
                assertNull("Nano diagnostics must not fill the debug home", fragment.findPreference<Preference>("nano_state"))
                assertNull("Candidates belong on their own page", fragment.findPreference<Preference>("copy_ranking"))
            }
            screenshot("brief-debug-home.png")
        }
        for ((page, keys) in listOf("AI" to listOf("nano_config", "cloud_config", "nano_state", "last_attempt"), "RANKING" to listOf("copy_ranking"), "REVIEW_UI" to listOf("brief_debug_review_scenario", "review_preview"),
            "REVIEW_ENGINE" to listOf("review_subject", "review_evidence", "review_cache", "review_checks", "regenerate_review"),
            "REVIEW_AI" to listOf("review_provider", "review_test", "review_test_result"), "REVIEW_LOG" to listOf("copy_review_log", "clear_review_log"))) {
            val intent = Intent(context, BriefDebugActivity::class.java).putExtra("brief_debug_page", page)
            ActivityScenario.launch<BriefDebugActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                    keys.forEach { assertNotNull("$page/$it", fragment.findPreference<Preference>(it)) }
                }
                instrumentation.waitForIdleSync(); Thread.sleep(400)
                scenario.onActivity { activity ->
                    val navigation = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.ImageButton>().first()
                    assertTrue("$page keeps navigation visible", navigation.isShown && navigation.getGlobalVisibleRect(android.graphics.Rect()))
                    var view: View? = navigation
                    while (view != null) {
                        assertTrue("$page navigation parent is visible: ${view.javaClass.simpleName}", view.alpha > 0.99f)
                        view = view.parent as? View
                    }
                }
                screenshot("brief-debug-${page.lowercase()}.png")
            }
        }
        ActivityScenario.launch(BriefDebugActivity::class.java).use { scenario ->
            scenario.onActivity { it.delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO }
            instrumentation.waitForIdleSync(); Thread.sleep(350)
            screenshot("brief-debug-home-light.png")
        }
    }

    @Test fun reviewPreviewsCoverFailuresAndRetainedResultsWithoutChangingTheUsersReviewOrProvider() = withDebug {
        val username = TwidgetStore.settings(context).username
        val before = BriefPublishedReviewStore.posts(context, username, 100).associate { post ->
            post.url to BriefPublishedReviewStore.read(context, BriefReviewTarget(username, postUrl = post.url))?.savedAt
        }
        val provider = BriefSettingsStore.provider(context)
        val key = BriefSettingsStore.cloudApiKey(context)
        for ((scenario, _) in BriefReviewDebugFixtures.scenarios) {
            val state = BriefReviewDebugFixtures.state(context, scenario)
            if (scenario.startsWith("error_")) {
                assertEquals(BriefReviewPhase.FAILED, state.phase)
                assertEquals(scenario.removePrefix("error_"), state.failure!!.name)
            }
            if (scenario == "refreshing") { assertEquals(BriefReviewPhase.GENERATING, state.phase); assertNotNull(state.result) }
            if (scenario == "generating") assertNull(state.result)
        }
        for (scenario in listOf("quoted", "generating", "refreshing", "error_LOCAL_ONLY", "error_MISSING_KEY")) {
            ActivityScenario.launch<BriefReviewDebugActivity>(BriefReviewDebugActivity.intent(context, scenario)).use { activity ->
                instrumentation.waitForIdleSync()
                activity.onActivity { host ->
                    assertTrue(host.findViewById<View>(R.id.review_root).isShown)
                    assertNotNull(findText(host.findViewById(R.id.review_root), host.getString(R.string.debug_synthetic_note)))
                    if (scenario == "quoted") assertNotNull(host.findViewById<View>(R.id.review_quoted_tweet_card))
                    if (scenario == "refreshing") assertNotNull(findText(host.findViewById(R.id.review_root), host.getString(R.string.review_next_steps_intro)))
                }
                screenshot("brief-debug-review-$scenario.png")
            }
        }
        assertEquals(provider, BriefSettingsStore.provider(context))
        assertTrue("The API key stays untouched", key == BriefSettingsStore.cloudApiKey(context))
        assertEquals(before, BriefPublishedReviewStore.posts(context, username, 100).associate { post ->
            post.url to BriefPublishedReviewStore.read(context, BriefReviewTarget(username, postUrl = post.url))?.savedAt
        })
        assertFalse(BriefReviewDebug.checks().contains("FAIL"))
    }

    @Test fun reviewAttemptLogPersistsOnlyMetadataAndStopsWhenDebugIsLocked() = withDebug {
        val state = BriefReviewDebugFixtures.state(context, "complete")
        BriefReviewDebugLog.state(context, state.subject!!.target, state)
        val log = BriefReviewDebugLog.entries(context)
        assertTrue(log.length() > 0)
        val latest = log.getJSONObject(0).toString()
        assertFalse(latest.contains(state.subject.input.text))
        assertFalse(latest.contains(state.result!!.feedback.possibleExplanation))
        debugEnabled(false)
        BriefReviewDebugLog.record(context, "SHOULD_NOT_RECORD", JSONObject().put("test", true))
        assertEquals(log.toString(), BriefReviewDebugLog.entries(context).toString())
        debugEnabled(true)
        context.getSharedPreferences("twidget_review_debug", Context.MODE_PRIVATE).edit().putString("events", "[1,null,{}]").commit()
        BriefReviewDebugLog.state(context, state.subject.target, state)
        assertEquals("A damaged log must not prevent recording a review state", "COMPLETE", BriefReviewDebugLog.entries(context).getJSONObject(0).getString("event"))
    }

    private fun withDebug(block: () -> Unit) {
        val prefs = context.getSharedPreferences("twidget_review_debug", Context.MODE_PRIVATE)
        val backup = File(context.cacheDir, "brief_debug_tools_test_backup.json")
        fun restore(saved: JSONObject) {
            debugEnabled(saved.getBoolean("debug"))
            prefs.edit().putString("events", saved.getString("events")).commit()
        }
        if (backup.isFile) { restore(JSONObject(backup.readText())); backup.delete() }
        val saved = JSONObject().put("debug", TwidgetStore.debugMenuUnlocked(context)).put("events", prefs.getString("events", "[]"))
        backup.writeText(saved.toString())
        debugEnabled(true)
        try { block() } finally { restore(saved); backup.delete() }
    }
    // Avoid the production setter's deliberate reset of fake-update/version debug options.
    private fun debugEnabled(value: Boolean) {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit().putBoolean("debug_menu_unlocked", value).commit()
    }
    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) findText(view.getChildAt(index), text)?.let { return it }
        return null
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(300)
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(context.getExternalFilesDir(null), name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
