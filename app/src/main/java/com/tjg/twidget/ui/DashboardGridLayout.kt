package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.GridLayout

/** Keeps weighted card widths valid through the drawer's provisional launch measurements. */
class DashboardGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GridLayout(context, attrs, defStyleAttr) {
    private var previousWidthSpec: Int? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (previousWidthSpec != widthMeasureSpec) {
            previousWidthSpec = widthMeasureSpec
            // GridLayout can discard its maximum-width constraint when the rail
            // first measures a narrow content pane. Reset its constraint graph
            // for the real width; requestLayout alone only resets cached values.
            columnCount = columnCount
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
