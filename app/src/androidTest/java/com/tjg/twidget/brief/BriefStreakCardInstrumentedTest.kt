package com.tjg.twidget.brief

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.StreakSnapshot
import com.tjg.twidget.main.StreakCardFactory
import com.tjg.twidget.ui.CardValueTextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class BriefStreakCardInstrumentedTest {
    @Test fun wideStreakKeepsCountUnitsAndStateVisibleAcrossThemesAndWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (language in listOf("en", "fr", "de")) for (scale in listOf(1f, 1.4f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    setLocale(Locale.forLanguageTag(language))
                    fontScale = scale
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val density = context.resources.displayMetrics.density
                val fixtures = listOf(
                    StreakSnapshot(12, true, null, longestStreak = 120),
                    StreakSnapshot(120, true, null, longestStreak = 120),
                    StreakSnapshot(12, false, null), StreakSnapshot(4, false, null), StreakSnapshot(0, false, null),
                    StreakSnapshot(1, true, null, longestStreak = 120),
                    StreakSnapshot(12_345, true, null, longestStreak = 20_000))
                for (snapshot in fixtures) for (widthDp in listOf(399, 300)) {
                    val card = StreakCardFactory.createBrief(context, snapshot)
                    val width = (widthDp * density).toInt()
                    repeat(2) {
                        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        card.layout(0, 0, width, card.measuredHeight)
                    }
                    val value = card.findViewById<CardValueTextView>(R.id.brief_streak_value)
                    val label = card.findViewById<TextView>(R.id.brief_streak_state)
                    val icon = card.findViewById<ImageView>(R.id.brief_streak_icon)
                    assertEquals(context.resources.getQuantityString(R.plurals.streak_days, snapshot.streak, snapshot.streak), value.text.toString())
                    assertTrue("The complete count and unit fit at $widthDp dp ($language)", value.paint.measureText(value.text.toString()) <= value.width + 1)
                    assertTrue("Status remains below the count", label.top >= (value.parent as View).bottom)
                    assertTrue("Status remains inside the card", label.bottom <= card.height - card.paddingBottom)
                    assertEquals((68 * density).roundToInt(), icon.width)
                    assertEquals(icon.width, icon.height)
                    assertEquals("Icon and count stay next to each other", icon.right, value.left)
                    assertTrue("The icon/count group is centred", kotlin.math.abs(icon.left + value.right - (value.parent as View).width) <= 1)
                    assertNotNull(icon.drawable)
                    assertTrue(card.contentDescription.toString().contains(value.text.toString()))
                    if (scale == 1f && language == "en" && ((snapshot.streak == 12 && snapshot.activeToday) || snapshot.streak == 4) && widthDp == 399) {
                        assertEquals((160 * density).roundToInt(), card.height)
                        val bitmap = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
                        card.draw(Canvas(bitmap))
                        File(context.getExternalFilesDir(null), "brief-streak-${snapshot.streak}-$night.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
    }
}
