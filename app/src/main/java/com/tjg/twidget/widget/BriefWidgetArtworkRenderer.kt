package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefSnapshot
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts

/** Draws the four Brief widget proportions from the Figma component set. */
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
        settings: TwidgetWidgetSettings,
        account: String,
        snapshot: BriefSnapshot?,
        dark: Boolean,
        drawBackground: Boolean,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawBackground) drawFallbackSurface(canvas, width, height, dark)

        val card = snapshot?.cards?.firstOrNull() ?: BriefCard(
            "empty",
            BriefCardType.SUMMARY,
            context.getString(R.string.brief_widget_empty_title),
            context.getString(R.string.brief_widget_empty_body),
            0,
        )
        val widgetLayout = layout(width / density, height / density)
        val pad = dp(context, if (widgetLayout == Layout.WIDE_STRIP) 14 else 10).toFloat()
        val iconSize = minOf(dp(context, 48).toFloat(), height - pad * 2f)
        val iconLeft = pad
        val iconTop = if (widgetLayout == Layout.WIDE_STRIP || widgetLayout == Layout.COMPACT_STRIP) {
            (height - iconSize) / 2f
        } else {
            pad
        }
        drawIcon(context, canvas, card.type, account, iconLeft, iconTop, iconSize)

        val primary = if (dark) Color.WHITE else Color.rgb(24, 24, 27)
        val secondary = if (dark) Color.argb(204, 255, 255, 255) else Color.rgb(94, 94, 99)
        val titleSize = when (widgetLayout) {
            Layout.COMPACT_STRIP -> 14f
            Layout.WIDE_STRIP -> 20f
            Layout.SQUARE -> 18f
            Layout.WIDE_TALL -> 24f
        }
        val bodySize = when (widgetLayout) {
            Layout.COMPACT_STRIP -> 11f
            Layout.WIDE_STRIP -> 13f
            Layout.SQUARE -> 14f
            Layout.WIDE_TALL -> 14f
        }
        val titlePaint = textPaint(context, settings, primary, 700).apply { textSize = titleSize * density }
        val bodyPaint = textPaint(context, settings, secondary, 400).apply { textSize = bodySize * density }
        val textLeft: Float
        val titleBaseline: Float
        val textWidth: Float
        if (widgetLayout == Layout.WIDE_STRIP || widgetLayout == Layout.COMPACT_STRIP) {
            textLeft = iconLeft + iconSize + dp(context, 12)
            textWidth = width - pad - textLeft
            titleBaseline = height / 2f - dp(context, 2)
        } else {
            textLeft = pad
            textWidth = width - pad * 2f
            titleBaseline = when (widgetLayout) {
                Layout.SQUARE -> height - dp(context, 57)
                else -> height - dp(context, 55)
            }.toFloat()
        }
        shrinkToFit(titlePaint, card.title, textWidth, 12f * density)
        canvas.drawText(ellipsize(card.title, titlePaint, textWidth), textLeft, titleBaseline, titlePaint)
        drawWrapped(
            canvas,
            card.body,
            bodyPaint,
            textLeft,
            titleBaseline + dp(context, 19),
            textWidth,
            if (widgetLayout == Layout.COMPACT_STRIP || widgetLayout == Layout.WIDE_STRIP) 1 else 2,
        )
        return bitmap
    }

    private fun drawFallbackSurface(canvas: Canvas, width: Int, height: Int, dark: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                if (dark) intArrayOf(0xD92A2A2E.toInt(), 0xD917171A.toInt())
                else intArrayOf(0xBFC5DCFF.toInt(), 0xD9FCFCFF.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), minOf(width, height) * .22f, minOf(width, height) * .22f, paint)
    }

    private fun drawIcon(
        context: Context,
        canvas: Canvas,
        type: BriefCardType,
        account: String,
        left: Float,
        top: Float,
        size: Float,
    ) {
        if (type == BriefCardType.TOP_FOLLOWER) {
            val avatar = TopFollowersStore.read(context, account).top.firstOrNull()?.avatarUrl.orEmpty()
            ProfileImageLoader.cachedCircularBitmap(context, avatar, size.toInt())?.let {
                canvas.drawBitmap(it, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
                return
            }
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 31) }
        canvas.drawRoundRect(RectF(left, top, left + size, top + size), size * .31f, size * .31f, bg)
        val path = Path().apply {
            moveTo(left + size * .54f, top + size * .20f)
            lineTo(left + size * .31f, top + size * .54f)
            lineTo(left + size * .47f, top + size * .54f)
            lineTo(left + size * .39f, top + size * .82f)
            lineTo(left + size * .70f, top + size * .42f)
            lineTo(left + size * .54f, top + size * .42f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 122, 255) })
    }

    private fun textPaint(context: Context, settings: TwidgetWidgetSettings, color: Int, weight: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            typeface = if (settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
                val font = ResourcesCompat.getFont(
                    context,
                    if (weight >= 700) R.font.google_sans_flex_bold else R.font.google_sans_flex_regular,
                ) ?: Typeface.DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(font, weight, false) else font
            } else TwidgetFonts.oneUiSans(context, weight)
        }

    private fun shrinkToFit(paint: Paint, text: String, width: Float, minimum: Float) {
        while (paint.textSize > minimum && paint.measureText(text) > width) paint.textSize -= 1f
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var shortened = text
        while (shortened.isNotEmpty() && paint.measureText("$shortened…") > width) shortened = shortened.dropLast(1)
        return "$shortened…"
    }

    private fun drawWrapped(canvas: Canvas, text: String, paint: Paint, left: Float, baseline: Float, width: Float, maxLines: Int) {
        val words = text.split(' ').filter(String::isNotBlank)
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (current.isNotBlank() && paint.measureText(candidate) > width) {
                lines += current
                current = word
            } else current = candidate
        }
        if (current.isNotBlank()) lines += current
        val lineHeight = paint.textSize * 1.2f
        lines.take(maxLines).forEachIndexed { index, line ->
            val lastOverflow = index == maxLines - 1 && lines.size > maxLines
            canvas.drawText(if (lastOverflow) ellipsize("$line…", paint, width) else line, left, baseline + lineHeight * index, paint)
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
