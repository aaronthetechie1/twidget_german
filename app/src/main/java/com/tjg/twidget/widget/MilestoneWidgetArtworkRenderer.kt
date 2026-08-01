package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.main.MilestoneCopyFactory
import com.tjg.twidget.main.MilestoneGoalStore
import com.tjg.twidget.main.MilestoneMetric
import com.tjg.twidget.main.MilestoneMetricResolver
import com.tjg.twidget.main.MilestonePerformanceState
import com.tjg.twidget.main.MilestonePolicy
import com.tjg.twidget.ui.TwidgetFonts
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

internal object MilestoneWidgetArtworkRenderer {
    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        settings: TwidgetWidgetSettings,
        account: String,
        dark: Boolean,
        drawBackground: Boolean,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val goal = MilestoneGoalStore.read(context, account)
        val stats = TwidgetStore.currentStats(context, account)
        val snapshot = MilestoneMetricResolver.resolve(
            context = context,
            account = account,
            metric = goal.metric,
            stats = stats,
            history = TwidgetStore.rangedHistory(context, account, HistoryRange.MONTH),
            analytics = AnalyticsClient.cached(context, account),
            imported = ImportedAnalyticsStore.recent(context, account, 30),
        )
        val progress = if (goal.configured) MilestonePolicy.progress(snapshot.value, goal.target) ?: 0 else 0
        val state = if (goal.configured) {
            MilestonePolicy.performanceState(snapshot.history)
        } else {
            MilestonePerformanceState.NEUTRAL
        }
        val accent = when (state) {
            MilestonePerformanceState.ACCELERATING -> Color.rgb(15, 207, 110)
            MilestonePerformanceState.DECELERATING -> Color.rgb(255, 103, 31)
            MilestonePerformanceState.NEUTRAL -> Color.rgb(24, 129, 255)
        }
        if (drawBackground) {
            WidgetArtworkRenderer.drawWidgetBackground(
                context = context,
                canvas = canvas,
                width = width,
                height = height,
                settings = settings,
                dark = dark,
            )
        }

        val target = formatValue(goal.metric, goal.target)
        val noun = metricNoun(goal.metric)
        val message = if (!goal.configured || goal.target <= 0.0) {
            context.getString(R.string.milestone_account_goals) to
                context.getString(R.string.milestone_setup_hint)
        } else {
            MilestoneCopyFactory.message(context, account, state, progress, target, noun)
                .let { it.title to it.body }
        }
        val widthDp = width / density
        val heightDp = height / density
        val compact = heightDp <= 110f
        val wideCompact = compact && widthDp > 230f
        val tallWide = !compact && widthDp > 230f
        val pad = dp(context, if (wideCompact) 14 else 10).toFloat()
        val iconDiameter = min(dp(context, 48).toFloat(), height - pad * 2f)
        val iconCenterX = pad + iconDiameter / 2f
        val iconCenterY = pad + iconDiameter / 2f
        drawArcAndIcon(
            context,
            canvas,
            iconCenterX,
            iconCenterY,
            iconDiameter,
            progress,
            accent,
            if (dark) Color.WHITE else Color.BLACK,
        )

        val primary = if (dark) Color.WHITE else Color.BLACK
        val secondary = Color.argb(204, Color.red(primary), Color.green(primary), Color.blue(primary))
        val titlePaint = textPaint(context, settings, primary, 700).apply {
            textSize = when {
                wideCompact || tallWide -> 24f
                compact -> 20f
                else -> 18f
            } * density
        }
        val bodyPaint = textPaint(context, settings, primary, 400).apply {
            textSize = when {
                tallWide -> 16f
                compact -> 12.5f
                else -> 14f
            } * density
        }
        val bodyEmphasisPaint = textPaint(context, settings, primary, 700).apply {
            textSize = bodyPaint.textSize
        }
        val handlePaint = textPaint(context, settings, secondary, 600).apply {
            textSize = 12f * density
        }
        val textLeft = if (compact) {
            iconCenterX + iconDiameter / 2f + dp(context, 14).toFloat()
        } else {
            pad
        }
        val textRight = width - pad
        val textWidth = (textRight - textLeft).coerceAtLeast(dp(context, 60).toFloat())
        val displayedTitle = if (wideCompact && goal.configured && goal.target > 0.0) {
            "${message.first} · $target"
        } else {
            message.first
        }
        shrinkToFit(titlePaint, displayedTitle, textWidth, 13f * density)
        val titleBaseline = if (compact) {
            if (wideCompact) {
                dp(context, 10).toFloat() + titlePaint.textSize * 0.82f
            } else {
                height / 2f - dp(context, 3).toFloat()
            }
        } else if (tallWide) {
            pad + iconDiameter + dp(context, 40).toFloat()
        } else {
            pad + iconDiameter + dp(context, 26).toFloat()
        }
        val titleLeft = if (wideCompact) {
            textLeft + (textWidth - titlePaint.measureText(displayedTitle)) / 2f
        } else {
            textLeft
        }
        canvas.drawText(displayedTitle, titleLeft, titleBaseline, titlePaint)

        if (!wideCompact) {
            val displayedBody = if (
                compact &&
                goal.configured &&
                goal.target > 0.0
            ) {
                "$target $noun goal"
            } else {
                message.second
            }
            drawEmphasizedWrapped(
                canvas,
                displayedBody,
                bodyPaint,
                bodyEmphasisPaint,
                target.takeIf { goal.configured && goal.target > 0.0 },
                textLeft,
                titleBaseline + dp(context, 5).toFloat() + bodyPaint.textSize,
                textWidth,
                if (compact) 1 else 2,
            )
        }

        if (wideCompact || !compact) {
            drawFooter(
                context = context,
                canvas = canvas,
                settings = settings,
                account = account,
                left = if (wideCompact) {
                    centeredFooterLeft(
                        context = context,
                        settings = settings,
                        account = account,
                        paint = handlePaint,
                        areaLeft = textLeft,
                        areaWidth = textWidth,
                    )
                } else {
                    pad
                },
                baseline = height - pad,
                primary = primary,
                paint = handlePaint,
            )
        }
        return bitmap
    }

    private fun centeredFooterLeft(
        context: Context,
        settings: TwidgetWidgetSettings,
        account: String,
        paint: Paint,
        areaLeft: Float,
        areaWidth: Float,
    ): Float {
        val logoSize = dp(context, 18).toFloat()
        val gap = dp(context, 6).toFloat()
        val handle = "@${account.trim().trimStart('@')}"
        val contentWidth = logoSize + gap + paint.measureText(handle)
        return areaLeft + (areaWidth - contentWidth) / 2f
    }

    private fun drawFooter(
        context: Context,
        canvas: Canvas,
        settings: TwidgetWidgetSettings,
        account: String,
        left: Float,
        baseline: Float,
        primary: Int,
        paint: Paint,
    ) {
        val logoSize = dp(context, 18).toFloat()
        val iconRes = when (settings.logo) {
            TwidgetStore.LOGO_TWITTER -> R.drawable.ic_logo_twitter
            else -> R.drawable.ic_logo_x
        }
        val logoTop = baseline - logoSize * 0.82f
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
            setTint(primary)
            setBounds(
                left.toInt(),
                logoTop.toInt(),
                (left + logoSize).toInt(),
                (logoTop + logoSize).toInt(),
            )
            draw(canvas)
        }
        val handle = "@${account.trim().trimStart('@')}"
        canvas.drawText(handle, left + logoSize + dp(context, 6), baseline, paint)
    }

    private fun drawArcAndIcon(
        context: Context,
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        diameter: Float,
        progress: Int,
        accent: Int,
        primary: Int,
    ) {
        val stroke = (diameter * 0.075f).coerceAtLeast(3f)
        val arcBounds = RectF(
            centerX - diameter / 2f + stroke,
            centerY - diameter / 2f + stroke,
            centerX + diameter / 2f - stroke,
            centerY + diameter / 2f - stroke,
        )
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
            color = Color.argb(64, Color.red(primary), Color.green(primary), Color.blue(primary))
        }
        canvas.drawArc(arcBounds, 156f, 228f, false, arcPaint)
        arcPaint.color = accent
        canvas.drawArc(arcBounds, 156f, 228f * progress.coerceIn(0, 100) / 100f, false, arcPaint)

        val iconSize = diameter * 0.47f
        ContextCompat.getDrawable(context, R.drawable.ic_milestone_goals)?.mutate()?.apply {
            setTint(primary)
            setBounds(
                (centerX - iconSize / 2f).toInt(),
                (centerY - iconSize / 2f).toInt(),
                (centerX + iconSize / 2f).toInt(),
                (centerY + iconSize / 2f).toInt(),
            )
            draw(canvas)
        }
    }

    private fun textPaint(
        context: Context,
        settings: TwidgetWidgetSettings,
        color: Int,
        weight: Int,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        typeface = if (settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
            val base = ResourcesCompat.getFont(
                context,
                if (weight >= 700) R.font.google_sans_flex_bold else R.font.google_sans_flex_regular,
            ) ?: Typeface.DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, weight, false)
            } else {
                base
            }
        } else {
            TwidgetFonts.oneUiSans(context, weight)
        }
    }

    private data class BodyWord(
        val text: String,
        val emphasized: Boolean,
    )

    private fun drawEmphasizedWrapped(
        canvas: Canvas,
        text: String,
        regularPaint: Paint,
        emphasisPaint: Paint,
        emphasis: String?,
        left: Float,
        firstBaseline: Float,
        maxWidth: Float,
        maxLines: Int,
    ) {
        val words = text.split(' ').filter { it.isNotBlank() }.map { word ->
            BodyWord(word, emphasis != null && word.contains(emphasis))
        }
        val spaceWidth = regularPaint.measureText(" ")
        val lines = mutableListOf<MutableList<BodyWord>>()
        var current = mutableListOf<BodyWord>()
        var currentWidth = 0f

        words.forEach { word ->
            val paint = if (word.emphasized) emphasisPaint else regularPaint
            val wordWidth = paint.measureText(word.text)
            val nextWidth = currentWidth + if (current.isEmpty()) wordWidth else spaceWidth + wordWidth
            if (current.isNotEmpty() && nextWidth > maxWidth) {
                lines += current
                current = mutableListOf(word)
                currentWidth = wordWidth
            } else {
                current += word
                currentWidth = nextWidth
            }
        }
        if (current.isNotEmpty()) lines += current
        val shown = lines.take(maxLines).map { it.toMutableList() }.toMutableList()
        if (lines.size > maxLines && shown.isNotEmpty()) {
            val last = shown.last()
            fun lineWidth(wordsOnLine: List<BodyWord>): Float =
                wordsOnLine.foldIndexed(0f) { index, width, word ->
                    val paint = if (word.emphasized) emphasisPaint else regularPaint
                    width + if (index == 0) 0f else spaceWidth + paint.measureText(word.text)
                }
            while (
                last.isNotEmpty() &&
                lineWidth(last) + regularPaint.measureText("…") > maxWidth
            ) {
                last.removeAt(last.lastIndex)
            }
            if (last.isNotEmpty()) {
                val tail = last.removeAt(last.lastIndex)
                last += tail.copy(text = "${tail.text}…")
            }
        }
        shown.forEachIndexed { lineIndex, line ->
            var x = left
            val baseline = firstBaseline + lineIndex * regularPaint.textSize * 1.18f
            line.forEachIndexed { wordIndex, word ->
                if (wordIndex > 0) x += spaceWidth
                val paint = if (word.emphasized) emphasisPaint else regularPaint
                canvas.drawText(word.text, x, baseline, paint)
                x += paint.measureText(word.text)
            }
        }
    }

    private fun shrinkToFit(paint: Paint, text: String, maxWidth: Float, minimum: Float) {
        while (paint.measureText(text) > maxWidth && paint.textSize > minimum) paint.textSize -= 1f
    }

    private fun formatValue(metric: MilestoneMetric, value: Double): String =
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(value * 100).toInt()}%"
        } else {
            NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())
        }

    private fun metricNoun(metric: MilestoneMetric): String = when (metric) {
        MilestoneMetric.FOLLOWERS -> "follower"
        MilestoneMetric.VERIFIED_FOLLOWERS -> "verified follower"
        MilestoneMetric.ENGAGEMENT_RATE -> "engagement rate"
        MilestoneMetric.IMPRESSIONS -> "impression"
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
