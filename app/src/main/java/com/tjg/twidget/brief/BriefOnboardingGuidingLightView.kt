package com.tjg.twidget.brief

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tjg.twidget.R

class BriefOnboardingGuidingLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowColor = ContextCompat.getColor(context, R.color.brief_onboarding_guiding_light)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val button = (parent as? View)?.findViewById<View>(R.id.brief_onboarding_continue) ?: return
        if (button.width <= 0 || button.height <= 0) return
        // Follow the measured button, including narrow windows and larger translated labels.
        val buttonBounds = RectF(
            (button.left - left).toFloat(),
            (button.top - top).toFloat(),
            (button.right - left).toFloat(),
            (button.bottom - top).toFloat(),
        )
        val radius = buttonBounds.height() / 2f
        drawGlow(canvas, buttonBounds, radius, 34f * density, 0x28)
        drawGlow(canvas, buttonBounds, radius, 21f * density, 0x48)
        drawGlow(canvas, buttonBounds, radius, 9f * density, 0x72)
    }

    private fun drawGlow(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        blurRadius: Float,
        alpha: Int,
    ) {
        paint.apply {
            color = Color.argb(alpha, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.maskFilter = null
    }
}
