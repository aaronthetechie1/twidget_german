package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefSnapshot
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts

/** Transparent content artwork for the four Figma Brief widget sizes. */
internal object BriefWidgetArtworkRenderer {
    enum class Layout { COMPACT_STRIP, WIDE_STRIP, SQUARE, WIDE_TALL }

    fun layout(widthDp: Float, heightDp: Float): Layout = when {
        heightDp <= 110f && widthDp <= 230f -> Layout.COMPACT_STRIP
        heightDp <= 110f -> Layout.WIDE_STRIP
        widthDp <= 230f -> Layout.SQUARE
        else -> Layout.WIDE_TALL
    }

    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        account: String,
        snapshot: BriefSnapshot?,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val card = snapshot?.cards?.firstOrNull() ?: BriefCard(
            id = "empty",
            type = BriefCardType.SUMMARY,
            title = context.getString(R.string.brief_widget_empty_title),
            body = context.getString(R.string.brief_widget_empty_body),
            score = 0,
        )
        val widgetLayout = layout(width / density, height / density)
        val pad = dp(context, if (widgetLayout == Layout.WIDE_STRIP || widgetLayout == Layout.COMPACT_STRIP) 14 else 10).toFloat()
        val iconSize = minOf(dp(context, 48).toFloat(), height - pad * 2f)
        drawStateIcon(
            context = context,
            canvas = canvas,
            type = card.type,
            account = account,
            left = pad,
            top = if (widgetLayout == Layout.WIDE_STRIP || widgetLayout == Layout.COMPACT_STRIP) (height - iconSize) / 2f else pad,
            size = iconSize,
        )

        when (widgetLayout) {
            Layout.WIDE_STRIP -> drawStripTitle(
                context, canvas, card.title, dp(context, 76).toFloat(), width - dp(context, 90).toFloat(), height, 24f, 1,
            )
            Layout.COMPACT_STRIP -> drawStripTitle(
                context, canvas, card.title, dp(context, 76).toFloat(), width - dp(context, 90).toFloat(), height, 16f, 2,
            )
            Layout.SQUARE -> drawTallCopy(context, canvas, card, pad, width - pad * 2f, height, 18f, 12f)
            Layout.WIDE_TALL -> drawTallCopy(context, canvas, card, pad, width - pad * 2f, height, 26f, 16f)
        }
        return bitmap
    }

    private fun drawStripTitle(
        context: Context,
        canvas: Canvas,
        title: String,
        left: Float,
        width: Float,
        height: Int,
        sizeSp: Float,
        maxLines: Int,
    ) {
        val paint = textPaint(context, 700, sizeSp)
        val lines = wrap(title, paint, width, maxLines)
        val lineHeight = paint.textSize * 1.15f
        val firstBaseline = (height - lines.size * lineHeight) / 2f - paint.fontMetrics.top
        lines.forEachIndexed { index, line ->
            val shown = if (index == lines.lastIndex) ellipsize(line, paint, width) else line
            canvas.drawText(shown, left + (width - paint.measureText(shown)) / 2f, firstBaseline + index * lineHeight, paint)
        }
    }

    private fun drawTallCopy(
        context: Context,
        canvas: Canvas,
        card: BriefCard,
        left: Float,
        width: Float,
        height: Int,
        titleSizeSp: Float,
        bodySizeSp: Float,
    ) {
        val titlePaint = textPaint(context, 700, titleSizeSp)
        val bodyPaint = textPaint(context, 400, bodySizeSp)
        val titleLines = wrap(card.title, titlePaint, width, 2)
        val bodyLines = wrap(card.body, bodyPaint, width, 2)
        val titleHeight = titlePaint.textSize * 1.12f * titleLines.size
        val bodyHeight = bodyPaint.textSize * 1.18f * bodyLines.size
        val gap = dp(context, 5).toFloat()
        val blockTop = height - dp(context, 10) - titleHeight - gap - bodyHeight
        var baseline = blockTop - titlePaint.fontMetrics.top
        titleLines.forEachIndexed { index, line ->
            canvas.drawText(ellipsize(line, titlePaint, width), left, baseline + index * titlePaint.textSize * 1.12f, titlePaint)
        }
        baseline = blockTop + titleHeight + gap - bodyPaint.fontMetrics.top
        bodyLines.forEachIndexed { index, line ->
            canvas.drawText(ellipsize(line, bodyPaint, width), left, baseline + index * bodyPaint.textSize * 1.18f, bodyPaint)
        }
    }

    private fun drawStateIcon(
        context: Context,
        canvas: Canvas,
        type: BriefCardType,
        account: String,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val avatarUrl = when (type) {
            BriefCardType.TOP_FOLLOWER -> TopFollowersStore.read(context, account).top.firstOrNull()?.avatarUrl.orEmpty()
            else -> TwidgetStore.currentStats(context, account).profileImage
        }
        ProfileImageLoader.cachedCircularBitmap(context, avatarUrl, size.toInt())?.let {
            canvas.drawBitmap(it, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
            return
        }

        if (type == BriefCardType.SLOWDOWN || type == BriefCardType.MILESTONE) {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(56, 122, 255)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = size * .09f
            }
            val inset = ring.strokeWidth / 2f
            canvas.drawArc(RectF(left + inset, top + inset, left + size - inset, top + size - inset), -90f, 285f, false, ring)
            ContextCompat.getDrawable(context, R.drawable.ic_milestone_goals)?.mutate()?.apply {
                setTint(Color.BLACK)
                val iconInset = (size * .25f).toInt()
                setBounds((left + iconInset).toInt(), (top + iconInset).toInt(), (left + size - iconInset).toInt(), (top + size - iconInset).toInt())
                draw(canvas)
            }
            return
        }

        val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 27) }
        canvas.drawOval(RectF(left, top, left + size, top + size), fallback)
        val initials = account.trimStart('@').take(2).uppercase().ifBlank { "T" }
        val paint = textPaint(context, 700, 14f).apply { color = Color.WHITE }
        val baseline = top + size / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(initials, left + (size - paint.measureText(initials)) / 2f, baseline, paint)
    }

    private fun textPaint(context: Context, weight: Int, sizeSp: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = sizeSp * context.resources.displayMetrics.scaledDensity
            typeface = TwidgetFonts.oneUiSans(context, weight)
        }

    private fun wrap(text: String, paint: Paint, width: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter(String::isNotBlank)
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > width && lines.size < maxLines - 1) {
                lines += current
                current = word
            } else current = candidate
        }
        if (current.isNotEmpty()) lines += current
        return lines.take(maxLines).ifEmpty { listOf("") }
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var value = text
        while (value.isNotEmpty() && paint.measureText("$value…") > width) value = value.dropLast(1)
        return "$value…"
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
