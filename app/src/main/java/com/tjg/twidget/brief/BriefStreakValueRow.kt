package com.tjg.twidget.brief

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.tjg.twidget.R
import com.tjg.twidget.ui.CardValueTextView

/** Centre the intrinsic icon/count group; only narrow the count when the row runs out of room. */
internal class BriefStreakValueRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val value = findViewById<CardValueTextView>(R.id.brief_streak_value)
        val icon = findViewById<android.view.View>(R.id.brief_streak_icon)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - icon.layoutParams.width).coerceAtLeast(1)
        value.layoutParams.width = minOf(value.preferredContentWidth(), available)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
