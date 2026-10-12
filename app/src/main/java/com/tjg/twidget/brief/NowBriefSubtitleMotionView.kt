package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.pow
import kotlin.math.roundToInt

class NowBriefSubtitleMotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val spans = mutableListOf<MotionCharacterSpan>()
    private var animator: ValueAnimator? = null
    private var preparedText = ""
    private var profile = SUBTITLE_PROFILE
    private var renderMotion = false

    fun prepareNowBriefMotion() {
        prepareMotion(SUBTITLE_PROFILE)
    }

    fun prepareNowBriefTitleMotion() {
        prepareMotion(TITLE_PROFILE)
    }

    private fun prepareMotion(motionProfile: MotionProfile) {
        animator?.cancel()
        profile = motionProfile
        val source = text?.toString().orEmpty()
        preparedText = source
        if (source.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            clearMotion()
            return
        }

        spans.clear()
        val gradientStartX = paddingLeft.toFloat()
        val gradientEndX = (width - paddingRight).toFloat()
            .takeIf { it > gradientStartX }
            ?: gradientStartX + paint.measureText(source)
        val gradientStartY = paddingTop.toFloat()
        val gradientEndY = (height - paddingBottom).toFloat()
            .takeIf { it > gradientStartY }
            ?: gradientStartY + textSize
        var sequence = 0
        var index = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val end = index + Character.charCount(codePoint)
            if (!Character.isWhitespace(codePoint)) {
                MotionCharacterSpan(
                    start = index,
                    end = end,
                    startDelayMs = sequence * profile.characterIntervalMs,
                    blurRadiusPx = dp(profile.maxBlurDp),
                    startScale = profile.startScale,
                    colors = profile.colors,
                    gradientStartX = gradientStartX,
                    gradientEndX = gradientEndX,
                    gradientStartY = gradientStartY,
                    gradientEndY = gradientEndY,
                ).also { span ->
                    spans += span
                }
            }
            sequence++
            index = end
        }
        renderMotion = true
        alpha = 1f
        translationY = 0f
        invalidate()
    }

    fun startNowBriefMotion(startDelayMs: Long) {
        startMotion(startDelayMs)
    }

    fun startNowBriefTitleMotion(startDelayMs: Long) {
        startMotion(startDelayMs)
    }

    private fun startMotion(startDelayMs: Long) {
        animator?.cancel()
        if (spans.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            clearMotion()
            return
        }
        val characterRevealDuration = spans.maxOf { it.startDelayMs } + profile.characterDurationMs
        val blackRevealStart = (characterRevealDuration - profile.blackRevealLeadInMs).coerceAtLeast(0L)
        val shaderFlowDuration = maxOf(
            profile.shaderFlowDurationMs ?: 0L,
            characterRevealDuration + if (profile.colors != null) 180L else 0L,
        )
        val blackRevealDuration = if (profile.colors != null) {
            profile.blackRevealDurationMs ?: shaderFlowDuration
        } else {
            0L
        }
        val totalDuration = maxOf(
            blackRevealStart + blackRevealDuration,
            shaderFlowDuration,
        )
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDuration
            startDelay = startDelayMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animation ->
                val elapsed = animation.currentPlayTime
                val shaderProgress = if (profile.shaderFlowDurationMs != null) {
                    SHADER_INTERPOLATOR.getInterpolation((elapsed.toFloat() / shaderFlowDuration).coerceIn(0f, 1f))
                } else {
                    0f
                }
                val blackRevealProgress = profile.colors
                    ?.let {
                        val revealProgress = ((elapsed - blackRevealStart).toFloat() / blackRevealDuration)
                            .coerceIn(0f, 1f)
                        BLACK_REVEAL_INTERPOLATOR.getInterpolation(revealProgress)
                    }
                    ?: 0f
                spans.forEach {
                    it.setElapsed(
                        elapsed,
                        profile.characterDurationMs,
                        shaderProgress,
                        blackRevealProgress,
                    )
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) {
                        renderMotion = false
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun clearMotion() {
        animator?.cancel()
        spans.forEach { it.finish() }
        renderMotion = false
        if (text?.toString() != preparedText) {
            text = preparedText
        }
        alpha = 1f
        translationY = 0f
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (!renderMotion) {
            super.onDraw(canvas)
            return
        }
        val currentLayout = layout ?: return
        val currentText = text ?: return
        val save = canvas.save()
        canvas.translate(
            (compoundPaddingLeft - scrollX).toFloat(),
            (extendedPaddingTop - scrollY).toFloat(),
        )
        spans.forEach { span ->
            val line = currentLayout.getLineForOffset(span.start)
            span.drawGlyph(
                canvas,
                currentText,
                currentLayout.getPrimaryHorizontal(span.start),
                currentLayout.getLineBaseline(line),
                paint,
            )
        }
        canvas.restoreToCount(save)
    }

    private class MotionCharacterSpan(
        val start: Int,
        val end: Int,
        val startDelayMs: Long,
        private val blurRadiusPx: Float,
        private val startScale: Float,
        private val colors: IntArray?,
        private val gradientStartX: Float,
        private val gradientEndX: Float,
        private val gradientStartY: Float,
        private val gradientEndY: Float,
    ) {
        private var progress = 0f
        private var shaderProgress = 0f
        private var blackRevealProgress = 0f

        fun setElapsed(
            elapsedMs: Long,
            durationMs: Long,
            newShaderProgress: Float,
            newBlackRevealProgress: Float,
        ) {
            val raw = ((elapsedMs - startDelayMs).toFloat() / durationMs)
                .coerceIn(0f, 1f)
            progress = APPEAR_INTERPOLATOR.getInterpolation(raw)
            shaderProgress = newShaderProgress
            blackRevealProgress = newBlackRevealProgress
        }

        fun finish() {
            progress = 1f
        }

        fun drawGlyph(
            canvas: Canvas,
            text: CharSequence,
            x: Float,
            y: Int,
            paint: Paint,
        ) {
            val savedAlpha = paint.alpha
            val savedMaskFilter = paint.maskFilter
            val savedShader = paint.shader
            val glyphWidth = paint.measureText(text, start, end)
            val scale = startScale + (1f - startScale) * progress
            val blur = blurRadiusPx * (1f - progress)
            val motionMaskFilter = if (blur > 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            if (colors != null) {
                val gradientWidth = gradientEndX - gradientStartX
                val phase = gradientWidth * (shaderProgress - 0.5f)
                canvas.save()
                canvas.scale(scale, scale, x + glyphWidth / 2f, y.toFloat())
                paint.shader = blueShader(colors, phase)
                paint.maskFilter = motionMaskFilter
                paint.alpha = (savedAlpha * progress).roundToInt()
                canvas.drawText(text, start, end, x, y.toFloat(), paint)
                if (blackRevealProgress > 0f) {
                    paint.shader = blackSweepShader()
                    canvas.drawText(text, start, end, x, y.toFloat(), paint)
                }
                canvas.restore()
            } else {
                canvas.save()
                canvas.scale(scale, scale, x + glyphWidth / 2f, y.toFloat())
                paint.maskFilter = motionMaskFilter
                paint.alpha = (savedAlpha * progress).roundToInt()
                canvas.drawText(text, start, end, x, y.toFloat(), paint)
                canvas.restore()
            }
            paint.alpha = savedAlpha
            paint.maskFilter = savedMaskFilter
            paint.shader = savedShader
        }

        private fun blueShader(colors: IntArray, phase: Float): Shader = LinearGradient(
            gradientStartX + phase,
            gradientStartY + phase,
            gradientEndX + phase,
            gradientEndY + phase,
            colors,
            null,
            Shader.TileMode.CLAMP,
        )

        private fun blackSweepShader(): Shader {
            val gradientWidth = gradientEndX - gradientStartX
            val sweepTravelProgress = blackRevealProgress.toDouble().pow(0.7).toFloat()
            val sweepOffset = gradientWidth * (sweepTravelProgress * 2f - 1f)
            val diagonalEndY = gradientStartY + gradientWidth
            return LinearGradient(
                gradientStartX + sweepOffset,
                gradientStartY + sweepOffset,
                gradientEndX + sweepOffset,
                diagonalEndY + sweepOffset,
                intArrayOf(
                    0xFF000000.toInt(),
                    0xD9000000.toInt(),
                    0x8C000000.toInt(),
                    0x3D000000.toInt(),
                    0x00000000,
                ),
                floatArrayOf(0f, 0.18f, 0.42f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private companion object {
        data class MotionProfile(
            val characterDurationMs: Long,
            val characterIntervalMs: Long,
            val maxBlurDp: Float,
            val startScale: Float,
            val colors: IntArray? = null,
            val shaderFlowDurationMs: Long? = null,
            val blackRevealLeadInMs: Long = 0L,
            val blackRevealDurationMs: Long? = null,
        )

        val SUBTITLE_PROFILE = MotionProfile(200L, 10L, 4f, 0.85f)
        val TITLE_PROFILE = MotionProfile(
            characterDurationMs = 600L,
            characterIntervalMs = 45L,
            maxBlurDp = 7f,
            startScale = 0.85f,
            colors = intArrayOf(0xFFB8EEFF.toInt(), 0xFF78CFFF.toInt(), 0xFF3F93FF.toInt(), 0xFFB8EEFF.toInt()),
            shaderFlowDurationMs = 1020L,
            blackRevealLeadInMs = 600L,
            blackRevealDurationMs = 4000L,
        )
        val APPEAR_INTERPOLATOR = PathInterpolator(0.33f, 1f, 0.68f, 1f)
        val SHADER_INTERPOLATOR = PathInterpolator(0.61f, 1f, 0.88f, 1f)
        val BLACK_REVEAL_INTERPOLATOR = PathInterpolator(0.35f, 0f, 0.65f, 1f)
    }
}
