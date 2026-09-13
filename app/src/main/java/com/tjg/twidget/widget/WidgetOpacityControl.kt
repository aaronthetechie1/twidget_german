package com.tjg.twidget.widget

import android.view.View
import androidx.appcompat.widget.SeslSeekBar
import com.tjg.twidget.R
import kotlin.math.abs

/** Shared opacity control used by widget configuration and Appearance defaults. */
internal object WidgetOpacityControl {
    val presets = intArrayOf(38, 102, 178, 240)

    fun closestLevel(alpha: Int): Int = presets.indices.minBy { abs(presets[it] - alpha) }

    fun bind(root: View, alpha: Int, onChanged: (Int) -> Unit) {
        val ticks = listOf(R.id.opacity_tick_0, R.id.opacity_tick_1, R.id.opacity_tick_2, R.id.opacity_tick_3)
            .map { root.findViewById<View>(it) }
        val thumb = root.findViewById<View>(R.id.opacity_thumb_visual)
        val slider = root.findViewById<SeslSeekBar>(R.id.opacity_slider)
        fun updateVisuals() {
            val level = slider.progress.coerceIn(presets.indices)
            ticks.forEachIndexed { index, tick -> tick.alpha = if (index == level) 0f else 1f }
            thumb.post {
                val tick = ticks[slider.progress.coerceIn(presets.indices)]
                thumb.translationX = tick.x + tick.width / 2f - thumb.width / 2f
            }
        }
        slider.alpha = 0f
        slider.progressDrawable?.alpha = 0
        slider.progress = closestLevel(alpha)
        updateVisuals()
        slider.setOnSeekBarChangeListener(object : SeslSeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeslSeekBar, progress: Int, fromUser: Boolean) {
                updateVisuals()
                onChanged(presets[progress.coerceIn(presets.indices)])
            }
            override fun onStartTrackingTouch(seekBar: SeslSeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeslSeekBar) = Unit
        })
    }
}
