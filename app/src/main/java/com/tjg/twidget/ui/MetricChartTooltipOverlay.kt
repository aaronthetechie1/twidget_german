package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.tjg.twidget.R
import kotlin.math.ceil

internal class MetricChartTooltipOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val horizontalPadding = 9f * density
    private val verticalPadding = 6f * density
    private val shadowPadding = 10f * density
    private val radius = 14f * density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_card_bg)
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = TwidgetFonts.forApp(context, 700)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_primary)
        setShadowLayer(6f * density, 0f, 3f * density, Color.argb(90, 0, 0, 0))
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_accent)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val bubbleBounds = RectF()
    private var label = ""

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    fun show(label: String, left: Float, top: Float) {
        this.label = label
        val textHeight = textPaint.fontMetrics.run { descent - ascent }
        val bubbleWidth = textPaint.measureText(label) + horizontalPadding * 2f
        val bubbleHeight = textHeight + verticalPadding * 2f
        val layoutParams = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        layoutParams.width = ceil(bubbleWidth + shadowPadding * 2f).toInt()
        layoutParams.height = ceil(bubbleHeight + shadowPadding * 2f).toInt()
        layoutParams.leftMargin = (left - shadowPadding).toInt()
        layoutParams.topMargin = (top - shadowPadding).toInt()
        this.layoutParams = layoutParams
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (label.isEmpty()) return
        bubbleBounds.set(
            shadowPadding,
            shadowPadding,
            width - shadowPadding,
            height - shadowPadding,
        )
        canvas.drawRoundRect(bubbleBounds, radius, radius, bubblePaint)
        canvas.drawRoundRect(bubbleBounds, radius, radius, strokePaint)
        canvas.drawText(
            label,
            bubbleBounds.left + horizontalPadding,
            bubbleBounds.top + verticalPadding - textPaint.fontMetrics.ascent,
            textPaint,
        )
    }
}
