package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.main.AboutActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowBriefMotionThemeInstrumentedTest {
    @Test fun subtitleUsesThemedTextColorDuringItsReveal() = checkThemes(title = false)

    @Test fun titleSettlesIntoThemedTextColorBeforeAnimationEnds() = checkThemes(title = true)

    @Test fun darkTitleSweepAlsoRendersWhiteOnTheHardwareCanvas() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var view: NowBriefSubtitleMotionView
            lateinit var animator: ValueAnimator
            scenario.onActivity { activity ->
                view = NowBriefSubtitleMotionView(activity).apply {
                    text = "A moment to reset"
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF073B57.toInt())
                }
                activity.addContentView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 240))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(view.isHardwareAccelerated)
                view.prepareNowBriefTitleMotion()
                view.startNowBriefTitleMotion(0)
                animator = animation(view)
            }
            android.os.SystemClock.sleep(50)
            scenario.onActivity {
                animator.currentPlayTime = animator.duration - 1
                animator.pause()
                view.invalidate()
            }
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(50)
            val copied = CountDownLatch(1)
            var result = -1
            lateinit var bitmap: Bitmap
            scenario.onActivity { activity ->
                val location = IntArray(2).also { view.getLocationInWindow(it) }
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window,
                    Rect(location[0], location[1], location[0] + view.width, location[1] + view.height), bitmap,
                    { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            try {
                assertTrue("Hardware frame must be copied", copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("The dark title must finish in white on the GPU",
                    pixels.count { Color.red(it) >= 250 && Color.green(it) >= 250 && Color.blue(it) >= 250 } > 100)
            } finally {
                bitmap.recycle()
                scenario.onActivity { animator.cancel() }
            }
        }
    }

    private fun checkThemes(title: Boolean) {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (dark in listOf(false, true)) {
            lateinit var view: NowBriefSubtitleMotionView
            lateinit var animator: ValueAnimator
            var expected = Color.TRANSPARENT
            instrumentation.runOnMainSync {
                val base = instrumentation.targetContext
                val configuration = Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_Twidget)
                expected = context.getColor(R.color.oneui_text_primary)
                view = NowBriefSubtitleMotionView(context).apply {
                    text = "A moment to reset"
                    textSize = 24f
                    setTextColor(expected)
                    measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
                    layout(0, 0, measuredWidth, measuredHeight)
                }
                if (title) view.prepareNowBriefTitleMotion() else view.prepareNowBriefMotion()
                if (title) view.startNowBriefTitleMotion(0) else view.startNowBriefMotion(0)
                animator = animation(view)
            }
            // Let the first animation frame establish its clock before seeking.
            android.os.SystemClock.sleep(50)
            instrumentation.runOnMainSync {
                try {
                    // Seek while custom drawing is still active, before TextView's normal
                    // draw can hide a wrong animation color at the final frame.
                    animator.currentPlayTime = if (title) animator.duration - 1 else 150L
                    assertTextColor(view, expected, "${if (title) "Title" else "Subtitle"}, dark=$dark, animated")
                    animator.end()
                    assertTextColor(view, expected, "${if (title) "Title" else "Subtitle"}, dark=$dark, finished")
                } finally {
                    animator.cancel()
                }
            }
        }
    }

    private fun animation(view: NowBriefSubtitleMotionView): ValueAnimator =
        NowBriefSubtitleMotionView::class.java.getDeclaredField("animator").run {
            isAccessible = true
            get(view) as ValueAnimator
        }

    private fun assertTextColor(view: View, expected: Int, label: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val ink = pixels.filter { Color.alpha(it) >= 128 }
            assertTrue("$label must draw visible text", ink.size > 100)
            val correct = ink.count {
                kotlin.math.abs(Color.red(it) - Color.red(expected)) <= 2 &&
                    kotlin.math.abs(Color.green(it) - Color.green(expected)) <= 2 &&
                    kotlin.math.abs(Color.blue(it) - Color.blue(expected)) <= 2
            }
            assertTrue("$label must use the themed text color throughout the reveal ($correct/${ink.size} pixels; " +
                "expected=${Integer.toHexString(expected)})",
                correct >= ink.size * 0.95)
        } finally {
            bitmap.recycle()
        }
    }
}
