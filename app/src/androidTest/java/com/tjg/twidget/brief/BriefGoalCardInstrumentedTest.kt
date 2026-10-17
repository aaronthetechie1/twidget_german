package com.tjg.twidget.brief

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.main.AccountGoalSettings
import com.tjg.twidget.main.MilestoneArcView
import com.tjg.twidget.main.MilestoneMetric
import com.tjg.twidget.main.MilestoneMetricSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BriefGoalCardInstrumentedTest {
    @Test fun goalCardKeepsItsNativeTitleAndVisibleNumericalProgressAtPhoneWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
            var card: View? = null
            scenario.onActivity { activity ->
                val root = FrameLayout(activity).apply { setBackgroundResource(R.drawable.brief_screen_background) }
                val column = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 60, 20, 20)
                }
                root.addView(column, FrameLayout.LayoutParams(BriefReviewViews.dp(activity, 360), FrameLayout.LayoutParams.WRAP_CONTENT))
                fun goal(current: Double?): View {
                    val view = LayoutInflater.from(activity).inflate(R.layout.milestone_card, column, false)
                    view.setBackgroundResource(R.drawable.brief_card_background)
                    view.findViewById<TextView>(R.id.milestone_title).text = "Followers are slightly down over the last week"
                    view.findViewById<TextView>(R.id.milestone_message).visibility = View.GONE
                    BriefGoalCardCopy.bind(activity, view, "goal_fixture", AccountGoalSettings(true, MilestoneMetric.FOLLOWERS, 200.0, false),
                        MilestoneMetricSnapshot(current, emptyList(), current != null))
                    view.findViewById<MilestoneArcView>(R.id.milestone_arc).progress = if (current == null) 0 else 80
                    column.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, BriefReviewViews.dp(activity, 112)).apply {
                        topMargin = BriefReviewViews.dp(activity, 16)
                    })
                    return view
                }
                card = goal(160.0)
                val unknown = goal(null)
                assertTrue(unknown.findViewById<TextView>(R.id.milestone_message).text.toString().contains("200"))
                assertFalse(unknown.findViewById<TextView>(R.id.milestone_message).text.toString().contains("0%"))
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val title = card!!.findViewById<TextView>(R.id.milestone_title)
                val detail = card!!.findViewById<TextView>(R.id.milestone_message)
                assertFalse(title.text.contains("Followers are slightly down"))
                assertEquals("160 / 200 followers · 80%", detail.text.toString())
                assertEquals(View.VISIBLE, detail.visibility)
                assertTrue(detail.isShown)
                assertNotNull(title.layout)
                assertEquals(0, title.layout.getEllipsisCount(0))
                assertEquals(0, detail.layout.getEllipsisCount(detail.layout.lineCount - 1))
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
            File(instrumentation.targetContext.getExternalFilesDir(null), "brief-goal-fixed.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
