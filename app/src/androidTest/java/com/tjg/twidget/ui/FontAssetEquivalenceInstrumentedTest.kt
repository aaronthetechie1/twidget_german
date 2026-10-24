package com.tjg.twidget.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontAssetEquivalenceInstrumentedTest {
    @Test fun pinnedAxesRetainTextMetricsAndGlyphOutlinesAcrossActiveStyles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = File(context.cacheDir, "audit-original-flex.ttf")
        instrumentation.context.assets.open("fonts/google_sans_flex.ttf").use { source -> original.outputStream().use(source::copyTo) }
        try {
            val before = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.Builder(original).build(); textSize = 48f }
            val after = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = TwidgetFonts.googleSansFlex(context); textSize = 48f }
            val texts = listOf("Followers 123,456,789", "Grüße für größere Ziele", "Français : cœur et progrès", "@account — 0.00% 👋")
            for (weight in listOf(200, 400, 500, 700, 1000)) for (width in listOf(25, 60, 100, 124, 151)) {
                for (slant in listOf(0, -10)) {
                    val roundness = if (weight >= 700) 100 else 0
                    val axes = "'wght' $weight, 'wdth' $width, 'ROND' $roundness, 'slnt' $slant"
                    before.fontVariationSettings = "$axes, 'GRAD' 0, 'opsz' 18"
                    after.fontVariationSettings = axes
                    texts.forEach { text ->
                        assertEquals("Text width for $axes / $text", before.measureText(text), after.measureText(text), 0.05f)
                        val a = Path().also { before.getTextPath(text, 0, text.length, 0f, 0f, it) }.approximate(0.01f)
                        val b = Path().also { after.getTextPath(text, 0, text.length, 0f, 0f, it) }.approximate(0.01f)
                        assertArrayEquals("Glyph outlines for $axes / $text", a, b, 0.05f)
                    }
                }
            }
        } finally { original.delete() }
    }
}
