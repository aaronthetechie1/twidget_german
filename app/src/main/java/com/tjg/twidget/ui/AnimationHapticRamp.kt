package com.tjg.twidget.ui

/** A finite fade-in envelope; timing remainders keep it aligned with the animation. */
internal data class AnimationHapticRamp(val timings: LongArray, val amplitudes: IntArray) {
    companion object {
        fun create(durationMs: Long, amplitudeControl: Boolean): AnimationHapticRamp {
            require(durationMs > 0)
            val steps = ((durationMs + 19) / 20).toInt().coerceIn(1, 512)
            val timings = mutableListOf<Long>()
            val amplitudes = mutableListOf<Int>()
            repeat(steps) { index ->
                val length = durationMs / steps + if (index < durationMs % steps) 1 else 0
                val progress = if (steps == 1) 1f else index.toFloat() / (steps - 1)
                val strength = progress * progress
                if (amplitudeControl) {
                    timings += length
                    amplitudes += (6 + 114 * strength).toInt()
                } else {
                    // Fixed-strength motors approximate the rise with increasing duty cycle.
                    val on = (length * (.06f + .54f * strength)).toLong().coerceIn(1, length)
                    timings += on
                    amplitudes += -1 // VibrationEffect.DEFAULT_AMPLITUDE
                    if (length > on) {
                        timings += length - on
                        amplitudes += 0
                    }
                }
            }
            return AnimationHapticRamp(timings.toLongArray(), amplitudes.toIntArray())
        }
    }
}
