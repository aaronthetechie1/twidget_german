package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Activity-owned continuation of the system splash.
 *
 * The three bar positions are the keyframes annotated in the Figma design.
 * Keeping this view above the launch skeleton makes initialization feel
 * intentional without delaying a launch that genuinely needs longer.
 */
internal class LaunchSplashView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startUptime = SystemClock.uptimeMillis()
    private var phase = 0f
    private var finishing = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = KEYFRAME_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val middleBlend = sin(PI * phase).toFloat().coerceIn(0f, 1f)
        val blueCoverage = lerp(0.26f, 0.63f, middleBlend)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(BACKGROUND_BLUE, BACKGROUND_BLUE, BACKGROUND_LIGHT),
            floatArrayOf(0f, blueCoverage, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val logoSize = min(dp(248f), width * 0.60f)
        val left = (width - logoSize) / 2f
        val top = (height - logoSize) / 2f
        val logo = RectF(left, top, left + logoSize, top + logoSize)
        paint.shader = LinearGradient(
            0f,
            logo.top,
            0f,
            logo.bottom,
            ICON_BLUE_TOP,
            ICON_BLUE_BOTTOM,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(logo, logoSize * 0.26f, logoSize * 0.26f, paint)

        drawBar(
            canvas = canvas,
            logo = logo,
            centerXFraction = 0.26f,
            heightFraction = lerp(0.234f, 0.315f, middleBlend),
            colorTop = Color.argb(210, 231, 249, 255),
            colorBottom = Color.argb(210, 153, 207, 238),
        )
        drawBar(
            canvas = canvas,
            logo = logo,
            centerXFraction = 0.50f,
            heightFraction = lerp(0.476f, 0.234f, middleBlend),
            colorTop = Color.WHITE,
            colorBottom = Color.rgb(238, 238, 238),
        )
        drawBar(
            canvas = canvas,
            logo = logo,
            centerXFraction = 0.74f,
            heightFraction = lerp(0.355f, 0.194f, middleBlend),
            colorTop = Color.argb(220, 237, 249, 255),
            colorBottom = Color.argb(220, 177, 218, 241),
        )
    }

    fun finishAfterMinimumDuration() {
        if (finishing) return
        finishing = true
        val elapsed = SystemClock.uptimeMillis() - startUptime
        postDelayed(::fadeOut, (MINIMUM_VISIBLE_MS - elapsed).coerceAtLeast(0L))
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun drawBar(
        canvas: Canvas,
        logo: RectF,
        centerXFraction: Float,
        heightFraction: Float,
        colorTop: Int,
        colorBottom: Int,
    ) {
        val barWidth = logo.width() * 0.138f
        val barHeight = logo.height() * heightFraction
        val centerX = logo.left + logo.width() * centerXFraction
        val bottom = logo.top + logo.height() * 0.734f
        val rect = RectF(centerX - barWidth / 2f, bottom - barHeight, centerX + barWidth / 2f, bottom)
        val radius = barWidth / 2f

        paint.shader = null
        paint.color = Color.argb(55, 0, 0, 0)
        canvas.drawRoundRect(
            RectF(rect.left, rect.top + dp(4f), rect.right, rect.bottom + dp(4f)),
            radius,
            radius,
            paint,
        )
        paint.shader = LinearGradient(
            0f,
            rect.top,
            0f,
            rect.bottom,
            colorTop,
            colorBottom,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun fadeOut() {
        animator.cancel()
        animate()
            .alpha(0f)
            .scaleX(1.035f)
            .scaleY(1.035f)
            .setDuration(EXIT_DURATION_MS)
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
            }
            .start()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    private companion object {
        private const val KEYFRAME_DURATION_MS = 900L
        private const val MINIMUM_VISIBLE_MS = 900L
        private const val EXIT_DURATION_MS = 220L
        private val BACKGROUND_BLUE = Color.rgb(141, 204, 255)
        private val BACKGROUND_LIGHT = Color.rgb(241, 241, 243)
        private val ICON_BLUE_TOP = Color.rgb(95, 183, 255)
        private val ICON_BLUE_BOTTOM = Color.rgb(0, 119, 216)
    }
}
