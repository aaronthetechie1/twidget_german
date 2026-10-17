package com.tjg.twidget.brief

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BriefPageChromeInstrumentedTest {
    @Test fun mainBriefBackStaysVisibleAndWorksAfterScrollingInBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun gesture(x: Int, start: Int, end: Int) {
            val down = android.os.SystemClock.uptimeMillis()
            for (step in 0..31) {
                val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 31 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                    x.toFloat(), start + (end - start) * step / 31f, 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
                if (start != end) android.os.SystemClock.sleep(16)
            }
        }
        var backs = 0
        ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(R.layout.activity_twidget_brief)
                val scroll = activity.findViewById<NestedScrollView>(R.id.brief_scroll)
                scroll.visibility = View.VISIBLE
                activity.findViewById<ViewGroup>(R.id.brief_content).addView(View(activity),
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BriefReviewViews.dp(activity, 3000)))
                val root = activity.findViewById<ViewGroup>(R.id.brief_root)
                com.tjg.twidget.ui.SeslToolbarCompatibility.install(root)
                val top = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.top ?: 0
                com.tjg.twidget.ui.SeslToolbarCompatibility.applyTopInset(root, top)
                scroll.seslSetFadingEdgeEnabled(false)
                BriefPageChrome.compact(root) { backs++ }
            }
            instrumentation.waitForIdleSync()
            var scrollBounds = Rect()
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.brief_scroll).getGlobalVisibleRect(scrollBounds)
            }
            gesture(scrollBounds.centerX(), scrollBounds.bottom - 150, scrollBounds.top + 250)
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            var previousScroll = 0
            fun assertBackVisible() {
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<Toolbar>(R.id.brief_back)
                    val floating = toolbar.parent as FloatingToolbarLayout
                    val back = (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<ImageButton>().first()
                    assertTrue("The back button stays on screen", back.getGlobalVisibleRect(Rect()))
                    assertTrue("The native container stays visible", floating.isShown && floating.alpha == 1f)
                    assertTrue("SESL manages the floating appearance", floating.isEnabledToolbarItemBackgroundTransition())
                    assertTrue("The native compact toolbar has no title", toolbar.title.isNullOrEmpty())
                    val appBar = activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.brief_app_bar)
                    assertFalse("The main page cannot expand its heading", appBar.isEnabled)
                    previousScroll = activity.findViewById<NestedScrollView>(R.id.brief_scroll).scrollY
                }
            }
            assertBackVisible()
            assertTrue("The gesture must scroll the Brief", previousScroll > 0)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "brief-main-scrolled-back.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val afterUp = previousScroll
            gesture(scrollBounds.centerX(), scrollBounds.top + 250, scrollBounds.bottom - 150)
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            assertBackVisible()
            assertTrue("The reverse gesture must move toward the top", previousScroll < afterUp)
            val backBounds = Rect()
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.brief_back)
                (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<ImageButton>().first().getGlobalVisibleRect(backBounds)
            }
            gesture(backBounds.centerX(), backBounds.centerY(), backBounds.centerY())
            instrumentation.waitForIdleSync()
            assertEquals("The visible back button is still tappable", 1, backs)
        }
    }
}
