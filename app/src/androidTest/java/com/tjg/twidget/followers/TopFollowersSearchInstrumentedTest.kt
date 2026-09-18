package com.tjg.twidget.followers

import android.app.Activity
import android.app.Instrumentation
import android.content.IntentFilter
import android.speech.RecognizerIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.ui.AppAppearance
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopFollowersSearchInstrumentedTest {
    @Test fun bottomSearchFiltersRestoresAndClearsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "native_search_test"
        val originalMode = AppAppearance.mode(context)
        TopFollowersArchiveStore.clear(context, username)
        TopFollowersArchiveStore.seedFromTop(context, username, (1..30).map {
            TopFollower("$it", "follower$it", "Follower $it", 10000L - it, false, "")
        })
        fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(500) }
        fun capture(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { AppAppearance.setMode(context, mode) }
                var compactWidth = 0
                ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                    .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username)).use { scenario ->
                    settle()
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        val toolbar = activity.findViewById<ToolbarLayout>(R.id.top_followers_browse_root).toolbar
                        assertFalse((0 until toolbar.menu.size()).any {
                            toolbar.menu.getItem(it).title == activity.getString(R.string.top_followers_browser_search)
                        })
                        assertFalse("Search must not autofocus on opening", search.hasFocus())
                        assertFalse("Idle mic uses the outline icon", search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).isSelected)
                        compactWidth = search.width
                        assertTrue("Idle search should be compact", search.width < activity.window.decorView.width)
                        assertEquals(View.VISIBLE, search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).visibility)
                        val bounds = Rect()
                        assertTrue(search.getGlobalVisibleRect(bounds))
                        val safe = ViewCompat.getRootWindowInsets(search)!!.getInsets(WindowInsetsCompat.Type.navigationBars())
                        assertTrue(bounds.bottom <= activity.window.decorView.height - safe.bottom)
                        assertNotNull("Floating search must have its native surface", search.findViewById<View>(androidx.appcompat.R.id.search_plate).background)
                    }
                    capture("followers-search-idle-$mode")
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        search.setQuery("follower30", false)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                    }
                    scenario.recreate()
                    settle()
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        assertEquals("follower30", search.query.toString())
                        assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                        search.findViewById<View>(androidx.appcompat.R.id.search_close_btn).performClick()
                        search.clearFocus()
                    }
                    settle()
                    scenario.onActivity { activity ->
                        activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).scrollToPosition(29)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                        val last = list.findViewHolderForAdapterPosition(29)!!.itemView
                        val rowBounds = Rect().also(last::getGlobalVisibleRect)
                        val searchBounds = Rect().also(activity.findViewById<View>(R.id.top_followers_browse_search)::getGlobalVisibleRect)
                        assertTrue("Last row must scroll fully above search", rowBounds.bottom <= searchBounds.top)
                    }
                    capture("followers-search-$mode")
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        assertEquals(30, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                        search.requestFocus()
                        ViewCompat.getWindowInsetsController(search)!!.show(WindowInsetsCompat.Type.ime())
                    }
                    settle()
                    capture("followers-search-keyboard-$mode")
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        val insets = ViewCompat.getRootWindowInsets(search)!!
                        assertTrue("Focused mic uses the filled icon", search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).isSelected)
                        assertTrue("Focused search should expand", search.width > compactWidth)
                        assertTrue("Keyboard must be shown", insets.isVisible(WindowInsetsCompat.Type.ime()))
                        val bounds = Rect()
                        search.getGlobalVisibleRect(bounds)
                        assertTrue("Search must sit above keyboard", bounds.bottom <= activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                        search.setQuery("no such follower", false)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.top_followers_browse_empty).visibility)
                        activity.findViewById<SearchView>(R.id.top_followers_browse_search).setQuery("", false)
                    }
                    val voiceMonitor = instrumentation.addMonitor(
                        IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                        Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS, arrayListOf("follower30"))), true)
                    try {
                        scenario.onActivity { activity ->
                            activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                                .findViewById<View>(androidx.appcompat.R.id.search_voice_btn).performClick()
                        }
                        settle()
                        assertEquals(1, voiceMonitor.hits)
                        scenario.onActivity { activity ->
                            assertEquals("follower30", activity.findViewById<SearchView>(R.id.top_followers_browse_search).query.toString())
                            assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                        }
                    } finally { instrumentation.removeMonitor(voiceMonitor) }
                }
            }
        } finally {
            TopFollowersArchiveStore.clear(context, username)
            instrumentation.runOnMainSync { AppAppearance.setMode(context, originalMode) }
        }
    }
}
