package com.tjg.twidget.widget

import android.content.Context
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.util.SeslRoundedCorner

/** Reveals the wallpaper surface without reading the protected wallpaper bitmap. */
class WallpaperPreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    var showWallpaper: Boolean = false
        set(value) {
            if (field == value) return
            wallpaperAnimator?.cancel()
            wallpaperAnimator = null
            placeholderOpacity = if (value) 0f else 1f
            field = value
            invalidate()
            rootView.invalidate()
        }
    private var wallpaperAnimator: ValueAnimator? = null
    private var placeholderOpacity = 1f
    private var placeholderTarget = 1f
    internal val wallpaperRevealFraction: Float get() = if (showWallpaper) 1f - placeholderOpacity else 0f

    fun setBackProgress(progress: Float) {
        wallpaperAnimator?.cancel()
        wallpaperAnimator = null
        placeholderOpacity = progress.coerceIn(0f, 1f)
        placeholderTarget = 1f
        invalidate()
        rootView.invalidate()
    }

    fun fadeInWallpaper() {
        if (showWallpaper && placeholderTarget == 0f) return
        if (!showWallpaper) {
            showWallpaper = true
            placeholderOpacity = 1f
        }
        animatePlaceholder(0f)
    }

    fun fadeOutWallpaper(onComplete: () -> Unit) {
        if (!showWallpaper) {
            onComplete()
            return
        }
        animatePlaceholder(1f, onComplete)
    }

    private fun animatePlaceholder(target: Float, onComplete: () -> Unit = {}) {
        wallpaperAnimator?.cancel()
        placeholderTarget = target
        wallpaperAnimator = ValueAnimator.ofFloat(placeholderOpacity, target).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                placeholderOpacity = it.animatedValue as Float
                invalidate()
                rootView.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onComplete()
                }
            })
            start()
        }
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.tjg.twidget.R.color.oneui_card_bg)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cornerRadius = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_rounded_corner_radius).toFloat()
    private var opening = Path()
    private val visibleBounds = Rect()
    private val lastVisibleBounds = Rect()
    private val position = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private val redrawOpening = ViewTreeObserver.OnPreDrawListener {
        getLocationInWindow(position)
        visibleBounds.setEmpty()
        getGlobalVisibleRect(visibleBounds)
        if (position[0] != lastX || position[1] != lastY || visibleBounds != lastVisibleBounds) {
            lastX = position[0]
            lastY = position[1]
            lastVisibleBounds.set(visibleBounds)
            // Scrolling normally reuses this view's display list. A clear operation
            // also changes pixels painted by ancestors, so repaint the window when
            // the opening moves or becomes visible again, including during flings.
            invalidate()
            rootView.invalidate()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(redrawOpening)
    }

    override fun onDetachedFromWindow() {
        wallpaperAnimator?.cancel()
        wallpaperAnimator = null
        viewTreeObserver.removeOnPreDrawListener(redrawOpening)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Use SESL's native contour anchored to the view, never Canvas.clipBounds:
        // the latter changes when a scroll parent clips or partially redraws us.
        opening = SeslRoundedCorner.getSmoothCornerRectPath(cornerRadius, 0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Clear directly into the window. An offscreen layer would only clear its
        // own texture and leave the opaque page underneath the preview.
        canvas.drawPath(opening, if (showWallpaper) clearPaint else placeholderPaint)
        if (showWallpaper && placeholderOpacity > 0f) {
            placeholderPaint.alpha = (placeholderOpacity * 255).toInt()
            canvas.drawPath(opening, placeholderPaint)
            placeholderPaint.alpha = 255
        }
        val save = canvas.save()
        canvas.clipPath(opening)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
